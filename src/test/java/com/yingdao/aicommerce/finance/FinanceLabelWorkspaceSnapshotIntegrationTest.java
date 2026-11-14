package com.yingdao.aicommerce.finance;

import java.lang.reflect.*;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import static com.yingdao.aicommerce.finance.FinanceData.*;
import static org.junit.jupiter.api.Assertions.*;

/** Proves that the real label workspace uses one read-only repeatable-read snapshot. */
@EnabledIfEnvironmentVariable(named="FINANCE_QA_JDBC",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinanceLabelWorkspaceSnapshotIntegrationTest {
    final FinanceConfigurationIntegrationTest fixture=new FinanceConfigurationIntegrationTest();

    @BeforeAll void migrate() throws Exception {fixture.migrate();}
    @BeforeEach void reset(){fixture.setup();}
    @AfterAll void close(){fixture.closeDataSource();}

    @Test void workspaceKeepsSourceFilesPeriodAndStateInOneSnapshotWhileAnotherTransactionPublishes() throws Exception {
        var scenario=seed();
        var source=new TrackingDataSource(fixture.dataSource);
        var jdbc=new PausingJdbc(source,Pause.AFTER_SOURCE);
        var labels=labels(jdbc);
        var current=readWhileMutating(labels,jdbc,Map.of(),()->publishNext(scenario));

        assertEquals(1,number(current.get("total")));
        assertEquals(1,rows(current.get("rows")).size());
        assertEquals("old-note",rows(current.get("rows")).getFirst().get("match_note"));
        assertEquals("old.xlsx",rows(current.get("rows")).getFirst().get("source_name"));
        assertEquals("READY",rows(current.get("rows")).getFirst().get("import_status"));
        assertEquals("old.xlsx",rows(current.get("files")).getFirst().get("source_name"));
        assertEquals("READY",rows(current.get("files")).getFirst().get("status"));
        assertEquals(0,decimal("11").compareTo(decimal(map(current.get("period")).get("amount_cny"))));
        assertEquals(1,number(map(current.get("state")).get("requested_revision")));
        assertEquals(1,number(map(current.get("state")).get("applied_revision")));
        assertEquals(false,current.get("processing"));
        assertSnapshotFlags(jdbc,7);

        var next=labels.workspace(fixture.owner,fixture.filter,Map.of());
        assertEquals(2,number(next.get("total")));
        assertEquals(2,rows(next.get("rows")).size());
        assertEquals("new-note",rows(next.get("rows")).getFirst().get("match_note"));
        assertEquals("new.xlsx",rows(next.get("files")).getFirst().get("source_name"));
        assertEquals("FAILED",rows(next.get("files")).getFirst().get("status"));
        assertEquals(0,decimal("22").compareTo(decimal(map(next.get("period")).get("amount_cny"))));
        assertEquals(2,number(map(next.get("state")).get("requested_revision")));
        assertEquals(1,number(map(next.get("state")).get("applied_revision")));
        assertEquals(true,next.get("processing"));
        assertEquals(0,fixture.dataSource.getHikariPoolMXBean().getActiveConnections());
    }

    @Test void detailsOnlyKeepsCountRowsAndEnrichmentInOneSnapshot() throws Exception {
        var scenario=seed();
        var source=new TrackingDataSource(fixture.dataSource);
        var jdbc=new PausingJdbc(source,Pause.AFTER_COUNT);
        var labels=labels(jdbc);
        var current=readWhileMutating(labels,jdbc,Map.of("detailsOnly","true"),()->publishNext(scenario));

        assertEquals(1,number(current.get("total")));
        assertEquals(1,rows(current.get("rows")).size());
        assertEquals("old-note",rows(current.get("rows")).getFirst().get("match_note"));
        assertEquals("old.xlsx",rows(current.get("rows")).getFirst().get("source_name"));
        assertEquals("READY",rows(current.get("rows")).getFirst().get("import_status"));
        assertEquals(Set.of("rows","total","page","pageSize"),current.keySet());
        assertSnapshotFlags(jdbc,4);

        var next=labels.workspace(fixture.owner,fixture.filter,Map.of("detailsOnly","true"));
        assertEquals(2,number(next.get("total")));
        assertEquals(2,rows(next.get("rows")).size());
        assertEquals("new-note",rows(next.get("rows")).getFirst().get("match_note"));
        assertEquals("new.xlsx",rows(next.get("rows")).getFirst().get("source_name"));
        assertEquals("FAILED",rows(next.get("rows")).getFirst().get("import_status"));
        assertEquals(0,fixture.dataSource.getHikariPoolMXBean().getActiveConnections());
    }

    @Test void workspaceFailureRollsBackAndReleasesItsSnapshotConnection() {
        seed();
        var source=new TrackingDataSource(fixture.dataSource);
        var jdbc=new PausingJdbc(source,Pause.FAIL_AFTER_SOURCE);
        var labels=labels(jdbc);
        var failure=assertThrows(IllegalStateException.class,
                ()->labels.workspace(fixture.owner,fixture.filter,Map.of()));
        assertEquals("forced workspace read failure",failure.getMessage());
        assertEquals(1,source.rollbacks.get(),"The failed read transaction must roll back");
        assertTrue(source.closes.get()>=1,"The failed snapshot connection must be returned to the pool");
        assertEquals(0,fixture.dataSource.getHikariPoolMXBean().getActiveConnections());

        jdbc.pause=Pause.NONE;
        assertEquals(1,number(labels.workspace(fixture.owner,fixture.filter,Map.of("detailsOnly","true")).get("total")));
        assertEquals(0,fixture.dataSource.getHikariPoolMXBean().getActiveConnections());
    }

    @Test void mutationFailureStopsTheReaderAndReleasesItsSnapshotConnection() {
        seed();
        var source=new TrackingDataSource(fixture.dataSource);
        var jdbc=new PausingJdbc(source,Pause.AFTER_SOURCE);
        var labels=labels(jdbc);

        var failure=assertThrows(IllegalStateException.class,()->readWhileMutating(labels,jdbc,Map.of(),()->{
            throw new IllegalStateException("forced mutation failure");
        }));

        assertEquals("forced mutation failure",failure.getMessage());
        assertTrue(jdbc.readerFinished,"The reader executor must finish before cleanup returns");
        assertEquals(0,fixture.dataSource.getHikariPoolMXBean().getActiveConnections());
    }

    private FinanceLabelCosts labels(JdbcTemplate jdbc){
        return new FinanceLabelCosts(new FinanceRepository(jdbc,new ObjectMapper()),null,
                Path.of("target","label-workspace-snapshot").toString());
    }

    private Map<String,Object> readWhileMutating(FinanceLabelCosts labels,PausingJdbc jdbc,
            Map<String,String> query,Runnable mutation) throws Exception {
        var executor=Executors.newSingleThreadExecutor(r->{var thread=new Thread(r,"label-workspace-snapshot-reader");thread.setDaemon(true);return thread;});
        Future<Map<String,Object>> future=null;Throwable primary=null;
        try{
            future=executor.submit(()->labels.workspace(fixture.owner,fixture.filter,query));
            assertTrue(jdbc.reached.await(10,TimeUnit.SECONDS),"Workspace did not reach the selected source read");
            mutation.run();
            jdbc.release.countDown();
            return future.get(20,TimeUnit.SECONDS);
        }catch(Exception|AssertionError failure){primary=failure;throw failure;}
        finally{
            AssertionError cleanup=null;
            jdbc.release.countDown();
            if(future!=null&&!future.isDone())future.cancel(true);
            executor.shutdownNow();
            try{
                jdbc.readerFinished=executor.awaitTermination(5,TimeUnit.SECONDS);
                if(!jdbc.readerFinished)cleanup=new AssertionError("Workspace reader executor did not stop");
            }catch(InterruptedException interrupted){
                Thread.currentThread().interrupt();cleanup=new AssertionError("Interrupted while stopping workspace reader",interrupted);
            }
            if(fixture.dataSource.getHikariPoolMXBean().getActiveConnections()!=0){
                var connectionFailure=new AssertionError("Workspace reader left an active database connection");
                if(cleanup==null)cleanup=connectionFailure;else cleanup.addSuppressed(connectionFailure);
            }
            if(cleanup!=null){if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}
        }
    }

    private Scenario seed(){
        String shop=fixture.connect(fixture.owner,false),file="old-file",order="snapshot-order";
        fixture.db.update("INSERT INTO finance_label_state(organization_id,active_generation,requested_revision,applied_revision) VALUES('org','old-generation',1,1)");
        fixture.db.update("INSERT INTO finance_label_order_totals(organization_id,generation,shop_id,order_id,amount_cny,pending_count,shipment_count) VALUES('org','old-generation',?,?,11,0,1)",shop,order);
        fixture.db.update("""
            INSERT INTO finance_records(id,organization_id,user_id,shop_id,platform,kind,external_id,order_id,statement_id,category,status,record_date,currency,amount,source,raw_json)
            VALUES('snapshot-settlement','org','u',?,'SHOPEE','SETTLEMENT','snapshot-settlement',?,'snapshot-statement','结算','SETTLED','2026-10-21','PHP',1,'API','{}')
            """,shop,order);
        fixture.db.update("""
            INSERT INTO finance_label_imports(id,organization_id,source_name,content_hash,storage_name,stored_bytes,status,row_count,matched_count,amount_cny,created_by)
            VALUES(?,'org','old.xlsx',?,'',0,'READY',1,1,2,'u')
            """,file,"a".repeat(64));
        insertRow(file,1,shop,order,"old-note");
        return new Scenario(shop,file,order);
    }

    private void publishNext(Scenario scenario){
        fixture.repo.transaction(()->{
            fixture.db.update("UPDATE finance_label_invoice_rows SET match_note='new-note',amount_cny=9 WHERE import_id=? AND source_row=1",scenario.file());
            insertRow(scenario.file(),2,scenario.shop(),scenario.order(),"second-note");
            fixture.db.update("UPDATE finance_label_imports SET source_name='new.xlsx',status='FAILED',row_count=2,matched_count=2,amount_cny=18 WHERE id=?",scenario.file());
            fixture.db.update("INSERT INTO finance_label_order_totals(organization_id,generation,shop_id,order_id,amount_cny,pending_count,shipment_count) VALUES('org','new-generation',?,?,22,2,3)",scenario.shop(),scenario.order());
            fixture.db.update("UPDATE finance_label_state SET active_generation='new-generation',requested_revision=2,applied_revision=1,error_message='new-state' WHERE organization_id='org'");
            return null;
        });
    }

    private void insertRow(String file,int sourceRow,String shop,String order,String note){
        fixture.db.update("""
            INSERT INTO finance_label_invoice_rows(import_id,source_row,organization_id,event_key,row_key,bucket_key,order_id,shop_hint,customer,warehouse,document_no,transaction_date,amount_cny,matched_shop_id,match_status,match_note,match_checked)
            VALUES(?,?,'org',?,?,?,?,'shop','customer','warehouse','document','2026-10-21',2,?,'MATCHED',?,TRUE)
            """,file,sourceRow,"event-"+sourceRow,"row-"+sourceRow,"bucket-"+sourceRow,order,shop,note);
    }

    private static void assertSnapshotFlags(PausingJdbc jdbc,int minimumSelects){
        assertTrue(jdbc.selects.get()>=minimumSelects,"Every expected workspace SELECT must be observed");
        assertTrue(jdbc.sameBoundConnection,"Every SELECT must use the same transaction-bound connection");
        assertTrue(jdbc.allReadOnly,"Every SELECT must use a read-only connection");
        assertTrue(jdbc.allNonAutoCommit,"Every SELECT must run inside the transaction");
        assertTrue(jdbc.allRepeatableRead,"Every SELECT must use repeatable read");
        assertTrue(jdbc.holderHasTimeout,"The bound ConnectionHolder must have a deadline");
        assertTrue(jdbc.firstDeadlineLeadMillis>=25_000&&jdbc.firstDeadlineLeadMillis<=31_000,
                "The actual snapshot deadline must be approximately 30 seconds, not 15 seconds or unlimited");
        assertTrue(jdbc.sameDeadline,"The deadline must not restart between SELECTs");
        assertTrue(jdbc.lastRemainingMillis<=jdbc.firstRemainingMillis+250,"The deadline must be shared across SELECTs");
    }

    record Scenario(String shop,String file,String order) {}
    enum Pause {NONE,AFTER_SOURCE,AFTER_COUNT,FAIL_AFTER_SOURCE}

    static final class PausingJdbc extends JdbcTemplate {
        final CountDownLatch reached=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicBoolean paused=new AtomicBoolean();
        final AtomicInteger selects=new AtomicInteger();
        volatile Pause pause;volatile Connection firstConnection;volatile ConnectionHolder firstHolder;
        volatile boolean sameBoundConnection=true,allReadOnly=true,allNonAutoCommit=true,allRepeatableRead=true;
        volatile boolean holderHasTimeout,sameDeadline=true,readerFinished;
        volatile long firstDeadline=-1,firstDeadlineLeadMillis=-1,firstRemainingMillis=-1,lastRemainingMillis=-1;
        PausingJdbc(DataSource source,Pause pause){super(source);this.pause=pause;}
        @Override public List<Map<String,Object>> queryForList(String sql,Object...args){
            captureConnection();
            var result=super.queryForList(sql,args);
            if(sql.startsWith("SELECT id FROM finance_label_imports"))after(Pause.AFTER_SOURCE);
            return result;
        }
        @Override public <T>T queryForObject(String sql,Class<T> type,Object...args){
            captureConnection();
            T result=super.queryForObject(sql,type,args);
            if(sql.startsWith("SELECT COUNT(*) FROM finance_label_invoice_rows"))after(Pause.AFTER_COUNT);
            return result;
        }
        @Override public Map<String,Object> queryForMap(String sql,Object...args){captureConnection();return super.queryForMap(sql,args);}
        private void after(Pause expected){
            boolean fail=pause==Pause.FAIL_AFTER_SOURCE&&expected==Pause.AFTER_SOURCE;
            if(pause!=expected&&!fail)return;
            if(!paused.compareAndSet(false,true))return;
            if(fail)throw new IllegalStateException("forced workspace read failure");
            reached.countDown();
            try{assertTrue(release.await(10,TimeUnit.SECONDS),"Mutation did not release the workspace read");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
        }
        private void captureConnection(){
            DataSource source=Objects.requireNonNull(getDataSource());
            Connection connection=DataSourceUtils.getConnection(source);
            try{
                selects.incrementAndGet();
                Object resource=TransactionSynchronizationManager.getResource(source);
                ConnectionHolder holder=resource instanceof ConnectionHolder current?current:null;
                boolean bound=holder!=null&&holder.getConnection()==connection&&DataSourceUtils.isConnectionTransactional(connection,source);
                long now=System.currentTimeMillis();
                if(firstConnection==null){
                    firstConnection=connection;firstHolder=holder;
                    holderHasTimeout=holder!=null&&holder.hasTimeout();
                    if(holderHasTimeout){
                        firstDeadline=holder.getDeadline().getTime();firstDeadlineLeadMillis=firstDeadline-now;
                        firstRemainingMillis=holder.getTimeToLiveInMillis();lastRemainingMillis=firstRemainingMillis;
                    }
                }else{
                    sameBoundConnection&=firstConnection==connection&&firstHolder==holder;
                    if(holder!=null&&holder.hasTimeout()){
                        sameDeadline&=holder.getDeadline().getTime()==firstDeadline;
                        lastRemainingMillis=holder.getTimeToLiveInMillis();
                    }else sameDeadline=false;
                }
                sameBoundConnection&=bound;
                allReadOnly&=connection.isReadOnly();allNonAutoCommit&=!connection.getAutoCommit();
                allRepeatableRead&=connection.getTransactionIsolation()==Connection.TRANSACTION_REPEATABLE_READ;
            }catch(Exception failure){throw new IllegalStateException(failure);}
            finally{DataSourceUtils.releaseConnection(connection,source);}
        }
    }

    static final class TrackingDataSource extends AbstractDataSource {
        final DataSource delegate;final AtomicInteger rollbacks=new AtomicInteger(),closes=new AtomicInteger();
        TrackingDataSource(DataSource delegate){this.delegate=delegate;}
        @Override public Connection getConnection() throws java.sql.SQLException {return track(delegate.getConnection());}
        @Override public Connection getConnection(String user,String password) throws java.sql.SQLException {return track(delegate.getConnection(user,password));}
        private Connection track(Connection connection){
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("rollback"))rollbacks.incrementAndGet();
                if(method.getName().equals("close"))closes.incrementAndGet();
                try{return method.invoke(connection,args);}catch(InvocationTargetException failure){throw failure.getCause();}
            });
        }
    }
}
