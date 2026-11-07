package com.yingdao.aicommerce.product.application;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="HEYI_DETAIL_STYLE_RECOVERY_TEST_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/heyi_detail_style_recovery_test[a-zA-Z0-9_]*")
class CommerceDetailStyleRecoverySqlTest {
    static JdbcTemplate db;static TransactionTemplate tx;
    @TempDir Path temp;
    CommerceModelDetailGridWorkflowTest h;
    AtomicLong now=new AtomicLong(System.currentTimeMillis());
    @BeforeAll static void schema()throws Exception{
        var source=new DriverManagerDataSource(System.getenv("HEYI_DETAIL_STYLE_RECOVERY_TEST_URL"),System.getenv().getOrDefault("HEYI_TEST_DB_USER","root"),System.getenv().getOrDefault("HEYI_TEST_DB_PASSWORD",""));
        try(var connection=source.getConnection()){
            try(var tables=connection.getMetaData().getTables(connection.getCatalog(),null,"%",new String[]{"TABLE"})){assertFalse(tables.next(),"Only an empty disposable database is accepted");}
            for(String file:List.of("V68__batch_execution_checkpoints.sql","V75__batch_artwork_jobs.sql","V96__batch_artwork_colors.sql"))
                ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/"+file));
            try(var statement=connection.createStatement()){statement.execute("CREATE TABLE commerce_batch_artwork_checks(job_id VARCHAR(36) PRIMARY KEY,report_json JSON NOT NULL,created_at TIMESTAMP(6) NOT NULL)");}
        }
        db=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
    }
    @BeforeEach void fixture(){
        for(String table:List.of("commerce_batch_artwork_checks","commerce_batch_artwork_jobs","commerce_batch_execution_events","commerce_batch_executions"))db.update("DELETE FROM "+table);
        h=new CommerceModelDetailGridWorkflowTest();h.temp=temp;h.setup();
        db.update("INSERT INTO commerce_batch_executions(id,organization_id,batch_id,created_by,stage,status,message,lease_token,lease_until,created_at,updated_at) VALUES('run','org','batch','owner','MOCKUP','RUNNING','fixture','lease',DATE_ADD(NOW(6),INTERVAL 20 MINUTE),NOW(6),NOW(6))");
        db.update("""
                INSERT INTO commerce_batch_artwork_jobs(id,organization_id,execution_id,capture_id,capture_version,kind,variant_index,color_key,request_key,status,prompt,model_config_id,model_fingerprint,reference_keys,storage_key,content_sha256,created_at,updated_at)
                VALUES('main','org','run','capture',7,'MOCKUP_FRONT',1,'black','main','READY','fixture','local','fingerprint','[]',?,?,NOW(6),NOW(6))
                """,h.product,h.product.substring(65));
        db.update("INSERT INTO commerce_batch_artwork_checks VALUES('main',?,NOW(6))",h.json.writeValueAsString(Map.of("passed",true,"otherEvidence","keep","sourceStylePreparation",Map.of("attempts",3,"nextAttemptAt",0))));
    }
    CommerceModelDetailGridWorkflow workflow(){
        var workflow=new CommerceModelDetailGridWorkflow(db,h.archive,h.json,tx,h.review);
        ReflectionTestUtils.setField(workflow,"styleGuide",h.styleGuide);ReflectionTestUtils.setField(workflow,"now",(java.util.function.LongSupplier)now::get);return workflow;
    }
    CommerceBatchStageHandler.Result execute(){return workflow().execute(h.run,h.source,1,db.queryForList("SELECT * FROM commerce_batch_artwork_jobs WHERE execution_id='run'"),id->h.product,
            (kind,prompt,refs,existing)->{throw new AssertionError("No generation during outage");});}
    tools.jackson.databind.JsonNode saved(){return h.json.readTree(db.queryForObject("SELECT report_json FROM commerce_batch_artwork_checks WHERE job_id='main'",String.class));}
    @Test void oldThreeAttemptCheckpointContinuesAcrossRestartsAndMysqlRoundTrips(){
        when(h.styleGuide.prepare(anyString(),any(byte[].class))).thenAnswer(call->{
            assertEquals("RUNNING",saved().path("sourceStylePreparation").path("status").asText());
            throw new CommerceModelDetailStyleGuide.UnavailableException("CHAT_TIMEOUT",true,false,"private body");
        });
        for(int attempt=4;attempt<=7;attempt++){
            assertEquals("QUEUED",execute().pendingStatus());assertEquals(attempt,saved().path("sourceStylePreparation").path("attempts").asInt());
            assertEquals(now.get()+300000,saved().path("sourceStylePreparation").path("nextAttemptAt").asLong());
            assertEquals("QUEUED",execute().pendingStatus());now.addAndGet(300000);
        }
        verify(h.styleGuide,times(4)).prepare(anyString(),any(byte[].class));assertTrue(saved().path("passed").asBoolean());assertEquals("keep",saved().path("otherEvidence").asText());
        assertFalse(saved().toString().contains("private"));
    }
    @Test void pausedExecutionNeverIssuesAnalysisAndConcurrentEvidenceSurvives(){
        db.update("UPDATE commerce_batch_executions SET status='PAUSING'");assertThrows(IllegalStateException.class,this::execute);verifyNoInteractions(h.styleGuide);
        db.update("UPDATE commerce_batch_executions SET status='RUNNING'");
        when(h.styleGuide.prepare(anyString(),any(byte[].class))).thenAnswer(call->{
            db.update("UPDATE commerce_batch_artwork_checks SET report_json=JSON_SET(report_json,'$.newerEvidence','keep') WHERE job_id='main'");
            throw new CommerceModelDetailStyleGuide.UnavailableException("CHAT_TIMEOUT",true,false,"timeout");
        });
        assertEquals("QUEUED",execute().pendingStatus());assertEquals("keep",saved().path("newerEvidence").asText());
        assertEquals(4,saved().path("sourceStylePreparation").path("attempts").asInt());
    }
}
