package com.yingdao.aicommerce.product.application;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="HEYI_IDENTITY_RECOVERY_TEST_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/heyi_identity_recovery_test[a-zA-Z0-9_]*")
class CommerceDetailIdentityRecoverySqlTest {
    static JdbcTemplate db;
    @TempDir Path temp;
    CommerceModelDetailWorkflowTest h;
    Map<String,Object> verdict;
    @BeforeAll static void schema()throws Exception{
        var source=new DriverManagerDataSource(System.getenv("HEYI_IDENTITY_RECOVERY_TEST_URL"),System.getenv().getOrDefault("HEYI_TEST_DB_USER","root"),System.getenv().getOrDefault("HEYI_TEST_DB_PASSWORD",""));
        try(var connection=source.getConnection()){
            try(var tables=connection.getMetaData().getTables(connection.getCatalog(),null,"%",new String[]{"TABLE"})){assertFalse(tables.next(),"Only an empty disposable database is accepted");}
            for(String file:List.of("V68__batch_execution_checkpoints.sql","V75__batch_artwork_jobs.sql","V96__batch_artwork_colors.sql"))ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/"+file));
            try(var statement=connection.createStatement()){statement.execute("CREATE TABLE commerce_batch_artwork_checks(job_id VARCHAR(36) PRIMARY KEY,report_json JSON NOT NULL,created_at TIMESTAMP(6) NOT NULL)");}
        }
        db=new JdbcTemplate(source);
    }
    @BeforeEach void fixture()throws Exception{
        for(String table:List.of("commerce_batch_artwork_checks","commerce_batch_artwork_jobs","commerce_batch_execution_events","commerce_batch_executions"))db.update("DELETE FROM "+table);
        h=new CommerceModelDetailWorkflowTest();h.temp=temp;h.setup();verdict=new LinkedHashMap<>(h.reports.get("detail-1"));
        db.update("INSERT INTO commerce_batch_executions(id,organization_id,batch_id,created_by,stage,status,message,lease_token,lease_until,created_at,updated_at) VALUES('run','org','batch','owner','MOCKUP','RUNNING','fixture','lease',DATE_ADD(NOW(6),INTERVAL 20 MINUTE),NOW(6),NOW(6))");
        var job=h.details.getFirst();
        db.update("""
                INSERT INTO commerce_batch_artwork_jobs(id,organization_id,execution_id,capture_id,capture_version,kind,variant_index,request_key,status,prompt,model_config_id,model_fingerprint,reference_keys,storage_key,content_sha256,created_at,updated_at)
                VALUES('detail-1','org','run','capture',7,'MODEL_DETAIL_1',1,'detail-1','READY',?,'grok','fingerprint',?,?,?,NOW(6),NOW(6))
                """,job.get("prompt"),job.get("reference_keys"),job.get("storage_key"),job.get("content_sha256"));
    }
    CommerceModelDetailWorkflow.ReviewOnly review(){return new CommerceModelDetailWorkflow(db,h.archive,h.json,h.reviewer,h.retry).reviewOnly(h.run,h.source,1,1,h.details.getFirst(),h.product,h.styles.getFirst());}
    tools.jackson.databind.JsonNode saved(){return h.json.readTree(db.queryForObject("SELECT report_json FROM commerce_batch_artwork_checks WHERE job_id='detail-1'",String.class));}
    @Test void legacyThreeRetriesContinueAndSuccessPreservesBoundStyleEvidence(){
        db.update("INSERT INTO commerce_batch_artwork_checks VALUES('detail-1',?,NOW(6))",h.json.writeValueAsString(Map.of("status","REVIEW_UNAVAILABLE","readAttempts",3,"nextAttemptAt",0,"sourceStyleMatch",Map.of("passed",true,"guideSha256","a".repeat(64)))));
        when(h.reviewer.review(anyString(),any(),any(),any())).thenAnswer(call->{assertEquals("REVIEW_RUNNING",saved().path("status").asText());assertEquals(4,saved().path("readAttempts").asInt());throw new CommerceModelDetailReview.ReviewUnavailableException("CHAT_TIMEOUT",true,false,"private provider");}).thenReturn(verdict);
        assertEquals("REVIEW_RETRY_PENDING",review().status());assertEquals(4,saved().path("readAttempts").asInt());assertTrue(saved().path("sourceStyleMatch").path("passed").asBoolean());
        db.update("UPDATE commerce_batch_artwork_checks SET report_json=JSON_SET(report_json,'$.nextAttemptAt',0) WHERE job_id='detail-1'");
        assertEquals("PASSED",review().status());assertEquals("a".repeat(64),saved().path("sourceStyleMatch").path("guideSha256").asText());assertFalse(saved().has("readAttempts"));
        assertTrue(review().cached());verify(h.reviewer,times(2)).review(anyString(),any(),any(),any());verifyNoInteractions(h.retry);
    }
    @Test void insertAndCasRejectLeaseAndConcurrentReportChanges(){
        when(h.reviewer.review(anyString(),any(),any(),any())).thenAnswer(call->{
            assertEquals("REVIEW_RUNNING",saved().path("status").asText());
            db.update("UPDATE commerce_batch_artwork_checks SET report_json=JSON_SET(report_json,'$.newerEvidence','keep') WHERE job_id='detail-1'");return verdict;
        });
        assertThrows(IllegalStateException.class,this::review);assertEquals("keep",saved().path("newerEvidence").asText());assertFalse(saved().has("passed"));
        db.update("DELETE FROM commerce_batch_artwork_checks");db.update("UPDATE commerce_batch_executions SET status='PAUSING'");clearInvocations(h.reviewer);
        assertThrows(IllegalStateException.class,this::review);verifyNoInteractions(h.reviewer,h.retry);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM commerce_batch_artwork_checks",Integer.class));
    }
}
