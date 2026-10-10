package com.yingdao.aicommerce.product.application;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses the production migrations and real MySQL JSON equality against an explicitly empty test database. */
@EnabledIfEnvironmentVariable(named="HEYI_DRAPE_RECOVERY_TEST_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/heyi_drape_recovery_test[a-zA-Z0-9_]*")
class CommerceDrapeLocalRecoverySqlTest {
    static JdbcTemplate db;
    CommerceDrapeReuseWorkflowTest.Harness h;
    @BeforeAll static void schema()throws Exception{
        var source=new DriverManagerDataSource(System.getenv("HEYI_DRAPE_RECOVERY_TEST_URL"),"root","");
        try(var connection=source.getConnection()){
            try(var tables=connection.getMetaData().getTables(connection.getCatalog(),null,"%",new String[]{"TABLE"})){assertFalse(tables.next(),"Only a new empty disposable database is accepted");}
            for(String file:List.of("V68__batch_execution_checkpoints.sql","V75__batch_artwork_jobs.sql","V96__batch_artwork_colors.sql","V97__commerce_mockup_derivatives.sql"))
                ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/"+file));
            try(var statement=connection.createStatement()){statement.execute("CREATE TABLE commerce_batch_artwork_checks(job_id VARCHAR(36) PRIMARY KEY,report_json JSON NOT NULL,created_at TIMESTAMP(6) NOT NULL)");}
        }
        db=new JdbcTemplate(source);
    }
    @BeforeEach void fixture()throws Exception{
        for(String table:List.of("commerce_batch_artwork_checks","commerce_mockup_derivatives","commerce_batch_artwork_jobs","commerce_batch_execution_events","commerce_batch_executions"))db.update("DELETE FROM "+table);
        h=new CommerceDrapeReuseWorkflowTest.Harness();
        db.update("INSERT INTO commerce_batch_executions(id,organization_id,batch_id,created_by,stage,status,message,lease_token,lease_until,created_at,updated_at) VALUES('run','org','batch','user','MOCKUP','RUNNING','local recovery fixture','lease',DATE_ADD(NOW(6),INTERVAL 20 MINUTE),NOW(6),NOW(6))");
        for(var job:h.jobs)db.update("""
                INSERT INTO commerce_batch_artwork_jobs(id,organization_id,execution_id,capture_id,capture_version,kind,variant_index,color_key,request_key,status,prompt,
                    model_config_id,model_fingerprint,reference_keys,result_json,storage_key,content_sha256,created_at,updated_at)
                VALUES(?,'org','run',?,?,?,?,?,?,'READY',?,'local','fingerprint',?,?,?,?,NOW(6),NOW(6))
                """,job.get("id"),job.get("capture_id"),job.get("capture_version"),job.get("kind"),job.get("variant_index"),job.get("color_key"),job.get("id"),Objects.toString(job.get("prompt"),"fixture"),job.get("reference_keys"),h.json.writeValueAsString(h.reports.get(job.get("id"))),job.get("storage_key"),job.get("content_sha256"));
    }
    String masterId(){return (String)h.find(CommerceDrapeReuseWorkflow.MASTER,0,null).get("id");}
    List<Map<String,Object>> jobs(){return db.queryForList("SELECT * FROM commerce_batch_artwork_jobs WHERE execution_id='run' AND organization_id='org' AND status<>'RETRIED' ORDER BY created_at,id");}
    CommerceDrapeReuseWorkflow workflow(CommerceDrapeStyleReview reviewer){return new CommerceDrapeReuseWorkflow(db,h.archive,h.json,h.processor,new CommerceLocalPhotoWorkflow(db,h.archive,h.json),reviewer,mock(CommerceDrapeMasterRetry.class),h.clock::get);}
    tools.jackson.databind.JsonNode state(){return h.json.readTree(db.queryForObject("SELECT report_json FROM commerce_batch_artwork_checks WHERE job_id=?",String.class,masterId()));}
    void seedPassedStyle(){
        db.update("UPDATE commerce_batch_artwork_jobs SET prompt=? WHERE id=?",CommerceDrapeReuseWorkflow.PROMPT,masterId());
        var report=new CommerceDrapeStyleWorkflowTest().report(h);report.put("localRecovery",Map.of("preservedEvidence","unchanged"));
        db.update("INSERT INTO commerce_batch_artwork_checks VALUES(?,?,NOW(6))",masterId(),h.json.writeValueAsString(report));
    }
    @Test void mysqlNormalizedJsonAndProductionJobFieldsResumePassedMotherAndRenderAllColors()throws Exception{
        seedPassedStyle();var reviewer=mock(CommerceDrapeStyleReview.class);
        when(h.processor.prepare(any(),any(),any(),any(),anyList())).thenThrow(new CommerceDrapeImageProcessor.LocalProcessingException("LOCAL_TIME_LIMIT",true,"local timeout",null)).thenAnswer(call->h.prepared());
        assertEquals("QUEUED",workflow(reviewer).finish(h.run,h.source,h.config,jobs()).pendingStatus());
        assertTrue(state().path("passed").asBoolean());assertEquals("unchanged",state().path("localRecovery").path("preservedEvidence").asText());
        assertEquals("QUEUED",workflow(reviewer).finish(h.run,h.source,h.config,jobs()).pendingStatus());
        h.clock.addAndGet(15000);
        for(int turn=0;turn<5;turn++)assertEquals("QUEUED",workflow(reviewer).finish(h.run,h.source,h.config,jobs()).pendingStatus());
        assertNull(workflow(reviewer).finish(h.run,h.source,h.config,jobs()));
        assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM commerce_batch_artwork_jobs WHERE kind='DRAPED_FRONT'",Integer.class));
        assertEquals(5,state().path("localRecovery").path("operations").size());
        for(var op:state().path("localRecovery").path("operations")){assertEquals("SUCCEEDED",op.path("status").asText());assertEquals(op.path("op").asText().equals("prepare")?2:1,op.path("attempts").asInt());}
        verifyNoInteractions(reviewer);verify(h.processor,times(2)).prepare(any(),any(),any(),any(),anyList());
    }
    @Test void initialInsertUsesActualMysqlJsonAndDoesNotRequireExistingCheck(){
        assertEquals("QUEUED",workflow(null).finish(h.run,h.source,h.config,jobs()).pendingStatus());
        assertEquals(1,state().path("localRecovery").path("operations").size());
    }
    @Test void currentTenantCreatorLeaseMasterHashAndGuideStateAreRequiredBeforeRequest()throws Exception{
        for(String mutation:List.of("UPDATE commerce_batch_executions SET organization_id='other'", "UPDATE commerce_batch_executions SET created_by='other'", "UPDATE commerce_batch_executions SET lease_token='new'", "UPDATE commerce_batch_executions SET lease_until=NOW(6)", "UPDATE commerce_batch_executions SET stage='REVIEW'", "UPDATE commerce_batch_executions SET status='PAUSING'", "UPDATE commerce_batch_artwork_jobs SET content_sha256=REPEAT('0',64) WHERE kind='DRAPE_MASTER'", "UPDATE commerce_batch_artwork_jobs SET status='RETRIED' WHERE kind='DRAPE_MASTER'", "UPDATE commerce_batch_artwork_jobs SET status='RETRIED' WHERE kind='DRAPE_GUIDE'")){
            fixture();var captured=jobs();db.update(mutation);
            assertEquals("UNKNOWN",workflow(null).finish(h.run,h.source,h.config,captured).pendingStatus(),mutation);
            verifyNoInteractions(h.processor);assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM commerce_batch_artwork_checks",Integer.class));
        }
    }
    @Test void concurrentReportChangeCannotBeOverwrittenAfterLocalRequest(){
        seedPassedStyle();
        when(h.processor.prepare(any(),any(),any(),any(),anyList())).thenAnswer(call->{
            db.update("UPDATE commerce_batch_artwork_checks SET report_json=JSON_SET(report_json,'$.newerEvidence','keep') WHERE job_id=?",masterId());return h.prepared();
        });
        assertEquals("UNKNOWN",workflow(null).finish(h.run,h.source,h.config,jobs()).pendingStatus());
        assertEquals("keep",state().path("newerEvidence").asText());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM commerce_batch_artwork_jobs WHERE kind='DRAPE_BLANK'",Integer.class));
    }
    @Test void newMasterCannotReceiveOrLoseOldLocalRecoveryEvidence(){
        var old=jobs();db.update("UPDATE commerce_batch_artwork_jobs SET status='RETRIED' WHERE kind='DRAPE_MASTER'");
        db.update("""
                INSERT INTO commerce_batch_artwork_jobs(id,organization_id,execution_id,capture_id,capture_version,kind,variant_index,request_key,status,prompt,model_config_id,model_fingerprint,reference_keys,created_at,updated_at)
                VALUES('new-master','org','run','capture',1,'DRAPE_MASTER',0,'new-master','PREPARED','new','grok','new','[]',NOW(6),NOW(6))
                """);
        assertEquals("UNKNOWN",workflow(null).finish(h.run,h.source,h.config,old).pendingStatus());verifyNoInteractions(h.processor);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM commerce_batch_artwork_checks",Integer.class));
        assertEquals("PREPARED",db.queryForObject("SELECT status FROM commerce_batch_artwork_jobs WHERE id='new-master'",String.class));
    }
}
