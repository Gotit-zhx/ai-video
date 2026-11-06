package com.yingdao.aicommerce.finance;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
class FinanceCostQueueIntegrationTest {
    static Connection connect()throws SQLException{
        String url=System.getenv("FINANCE_QA_JDBC");
        if(url==null||!url.matches("jdbc:mysql://[^/]+/finance_qa_[a-f0-9]+\\?.*"))throw new IllegalStateException("Disposable finance_qa database required");
        var connection=DriverManager.getConnection(url,System.getenv("FINANCE_QA_USER"),System.getenv("FINANCE_QA_PASSWORD"));
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.createStatement().execute("SET SESSION innodb_lock_wait_timeout=2");
        return connection;
    }
    static JdbcTemplate db(Connection connection){return new JdbcTemplate(new SingleConnectionDataSource(connection,true));}
    static FinanceOrderCosts costs(Connection connection){return new FinanceOrderCosts(new FinanceRepository(db(connection),new ObjectMapper()));}
    static List<Map<String,Object>> claim(Connection connection,int size,int page)throws SQLException{
        assertTrue(connection.getAutoCommit());
        var candidates=costs(connection).candidateJobs(size,page);
        connection.setAutoCommit(false);
        return costs(connection).claimJobs(candidates);
    }
    @BeforeAll static void create()throws Exception{
        try(var c=connect()){
            db(c).execute("""
                CREATE TABLE finance_order_cost_jobs (
                  order_record_id CHAR(64) PRIMARY KEY,organization_id CHAR(36) NOT NULL,shop_id CHAR(36) NOT NULL,
                  priority TINYINT NOT NULL DEFAULT 0,requested_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                  INDEX idx_cost_job_time(priority,requested_at,order_record_id),INDEX idx_cost_job_shop(organization_id,shop_id)
                ) ENGINE=InnoDB
                """);
            db(c).execute("CREATE TABLE finance_queue_source(id INT PRIMARY KEY,revision INT NOT NULL) ENGINE=InnoDB");
            db(c).update("INSERT INTO finance_queue_source VALUES(1,1)");
        }
    }
    @BeforeEach void seed()throws Exception{
        try(var c=connect()){
            db(c).update("DELETE FROM finance_order_cost_jobs");
            db(c).update("UPDATE finance_queue_source SET revision=1 WHERE id=1");
            for(int i=0;i<6;i++)db(c).update("INSERT INTO finance_order_cost_jobs(order_record_id,organization_id,shop_id,priority,requested_at) VALUES(?,'org','shop',1,'2026-01-01')","job-"+i);
        }
    }
    @AfterAll static void remove()throws Exception{try(var c=connect()){db(c).execute("DROP TABLE finance_order_cost_jobs,finance_queue_source");}}

    @Test void claimingExistingJobsDoesNotBlockUnrelatedHigherPriorityEnqueue()throws Exception{
        try(var holder=connect();var executor=Executors.newSingleThreadExecutor()){
            try{
                assertEquals(2,claim(holder,2,0).size());
                var inserted=executor.submit(()->{try(var other=connect()){
                    return db(other).update("INSERT INTO finance_order_cost_jobs(order_record_id,organization_id,shop_id) VALUES('new-job','org','shop')");
                }});
                assertEquals(1,inserted.get(1500,TimeUnit.MILLISECONDS),"unrelated enqueue must not wait for the whole cost batch");
            }finally{holder.rollback();}
        }
    }
    @Test void anotherWorkerCanSkipClaimedCandidatesAndRollbackKeepsEveryJob()throws Exception{
        try(var first=connect();var second=connect()){
            try{
                var a=claim(first,2,0);assertTrue(claim(second,2,0).isEmpty());
                second.rollback();second.setAutoCommit(true);
                var b=claim(second,2,1);
                assertEquals(2,a.size());assertEquals(2,b.size());
                var ids=new HashSet<Object>();a.forEach(row->ids.add(row.get("order_record_id")));
                assertTrue(b.stream().noneMatch(row->ids.contains(row.get("order_record_id"))));
            }finally{first.rollback();second.rollback();}
        }
        try(var c=connect()){assertEquals(6,db(c).queryForObject("SELECT COUNT(*) FROM finance_order_cost_jobs",Integer.class));}
    }
    @Test void sameOrderEnqueueWaitsForCompletionThenRemainsDurable()throws Exception{
        try(var holder=connect();var executor=Executors.newSingleThreadExecutor()){
            try{
                String id=claim(holder,1,0).getFirst().get("order_record_id").toString();
                var started=new CountDownLatch(1);
                var enqueue=executor.submit(()->{try(var other=connect()){
                    started.countDown();return db(other).update("INSERT INTO finance_order_cost_jobs(order_record_id,organization_id,shop_id) VALUES(?,'org','shop') ON DUPLICATE KEY UPDATE priority=0",id);
                }});
                assertTrue(started.await(1,TimeUnit.SECONDS));Thread.sleep(100);assertFalse(enqueue.isDone());
                db(holder).update("DELETE FROM finance_order_cost_jobs WHERE order_record_id=?",id);holder.commit();
                assertEquals(1,enqueue.get(2,TimeUnit.SECONDS));
                try(var check=connect()){assertEquals(1,db(check).queryForObject("SELECT COUNT(*) FROM finance_order_cost_jobs WHERE order_record_id=?",Integer.class,id));}
            }finally{holder.rollback();}
        }
    }
    @Test void financialSnapshotIncludesAnUpdateCommittedBetweenEnumerationAndClaim()throws Exception{
        try(var worker=connect()){
            var candidates=costs(worker).candidateJobs(1,0);
            try(var writer=connect()){db(writer).update("UPDATE finance_queue_source SET revision=2 WHERE id=1");}
            worker.setAutoCommit(false);
            try{
                assertEquals(1,costs(worker).claimJobs(candidates).size());
                assertEquals(2,db(worker).queryForObject("SELECT revision FROM finance_queue_source WHERE id=1",Integer.class));
            }finally{worker.rollback();}
        }
    }
}
