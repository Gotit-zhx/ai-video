package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt-in transaction/locking tests; only a newly created empty disposable MySQL schema is accepted. */
@EnabledIfEnvironmentVariable(named = "HEYI_ARTWORK_RETRY_TEST_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/heyi_artwork_retry_test[a-zA-Z0-9_]*")
class CommerceBatchArtworkRetryIntegrationTest {
    static DriverManagerDataSource source;
    static JdbcTemplate db;
    static DataSourceTransactionManager transactions;
    final PlatformPrincipal user = CommerceBatchArtworkRetryServiceTest.user("org", "u", List.of("product:edit", "product:publish"));
    final CommerceBatchExecutionService access = mock(CommerceBatchExecutionService.class);

    @BeforeAll static void isolatedSchema() throws Exception {
        String url = System.getenv("HEYI_ARTWORK_RETRY_TEST_URL");
        assertNotNull(url);
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/heyi_artwork_retry_test[a-zA-Z0-9_]*"));
        source = new DriverManagerDataSource(url, "root", "");
        try (var connection = source.getConnection()) {
            try (var tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
                assertFalse(tables.next(), "Only a new empty heyi_artwork_retry_test schema is allowed");
            }
            for (String migration : List.of("V68__batch_execution_checkpoints.sql", "V73__batch_model_call_limits.sql",
                    "V75__batch_artwork_jobs.sql", "V78__batch_artwork_repairs.sql", "V96__batch_artwork_colors.sql"))
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + migration));
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE commerce_batch_artwork_checks (job_id VARCHAR(36) PRIMARY KEY, report_json JSON NOT NULL, created_at TIMESTAMP(6) NOT NULL)");
            }
        }
        db = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
    }

    @BeforeEach void fixture() {
        for (String table : List.of("commerce_batch_artwork_checks", "commerce_batch_artwork_repairs", "commerce_batch_execution_events",
                "commerce_batch_artwork_jobs", "commerce_batch_model_reservations", "commerce_batch_model_limits", "commerce_batch_executions"))
            db.update("DELETE FROM " + table);
        db.update("INSERT INTO commerce_batch_executions(id,organization_id,batch_id,created_by,stage,status,message,lease_token,lease_until,created_at,updated_at) VALUES('run','org','batch','u','MOCKUP','FAILED','provider rejected','expired-token',NOW(6),NOW(6),NOW(6))");
        db.update("INSERT INTO commerce_batch_model_limits(execution_id,organization_id,max_calls,reserved_calls,created_at) VALUES('run','org',5,3,NOW(6))");
        db.update("INSERT INTO commerce_batch_model_reservations(execution_id,request_key,stage,created_at) VALUES('run','original-request','MOCKUP',NOW(6))");
        db.update("""
                INSERT INTO commerce_batch_artwork_jobs(id,organization_id,execution_id,capture_id,capture_version,kind,
                variant_index,request_key,status,prompt,model_config_id,model_fingerprint,reference_keys,result_json,message,created_at,updated_at)
                VALUES('old','org','run','capture',7,'MOCKUP',2,'original-request','REJECTED','frozen prompt 原始提示',
                'frozen-model','fixed-fingerprint','["original/image-a","original/image-b"]','{"requestState":"REJECTED"}','original rejection',NOW(6),NOW(6))
                """);
        db.update("""
                INSERT INTO commerce_batch_artwork_jobs(id,organization_id,execution_id,capture_id,capture_version,kind,
                variant_index,request_key,status,prompt,model_config_id,model_fingerprint,reference_keys,storage_key,content_sha256,message,created_at,updated_at)
                VALUES('ready','org','run','capture',7,'EXTRACT',0,'completed-request','READY','previous prompt',
                'frozen-model','fixed-fingerprint','[]','original/ready','ready-hash','already archived',NOW(6),NOW(6))
                """);
        db.update("INSERT INTO commerce_batch_artwork_checks(job_id,report_json,created_at) VALUES('ready',?,NOW(6))", "{\"pass\":true}");
        db.update("""
                INSERT INTO commerce_batch_artwork_repairs(id,organization_id,execution_id,job_id,request_key,storage_key,content_sha256,policy_json,created_at)
                VALUES('repair','org','run','ready','repair-request','original/repair','repair-hash','{"algorithm":"LOCAL_MASK_HARMONIC_V2"}',NOW(6))
                """);
        when(access.get("batch", user)).thenReturn(CommerceBatchArtworkRetryServiceTest.run("FAILED", "u"));
    }

    @Test void clonesFrozenInputsAndKeepsArchivedImagesChecksAndRepairs() {
        String references = new ObjectMapper().writeValueAsString(List.of(
                CommerceSurfaceTemplateInputs.key("org", "a".repeat(64)),
                CommerceSurfaceTemplateInputs.key("org", "b".repeat(64)),
                CommerceSurfaceTemplateInputs.key("org", "c".repeat(64))));
        db.update("UPDATE commerce_batch_artwork_jobs SET color_key='blue',color_label='蓝色',reference_keys=? WHERE id='old'", references);
        var old = job("old");
        var completed = job("ready");
        var check = db.queryForMap("SELECT * FROM commerce_batch_artwork_checks WHERE job_id='ready'");
        var repair = db.queryForMap("SELECT * FROM commerce_batch_artwork_repairs WHERE id='repair'");
        var result = service(db).retry("batch", "old", user);
        var fresh = job(result.artifactId());

        for (String field : List.of("organization_id", "execution_id", "capture_id", "capture_version", "kind", "variant_index",
                "prompt", "model_config_id", "model_fingerprint", "reference_keys", "color_key", "color_label"))
            assertEquals(old.get(field), fresh.get(field), field + " must remain frozen");
        assertEquals("PREPARED", fresh.get("status"));
        assertNull(fresh.get("result_json"));
        assertNull(fresh.get("storage_key"));
        assertEquals("RETRIED", job("old").get("status"));
        assertEquals(old.get("result_json"), job("old").get("result_json"));
        assertEquals(old.get("message"), job("old").get("message"));
        assertEquals(completed, job("ready"));
        assertEquals(check, db.queryForMap("SELECT * FROM commerce_batch_artwork_checks WHERE job_id='ready'"));
        assertEquals(repair, db.queryForMap("SELECT * FROM commerce_batch_artwork_repairs WHERE id='repair'"));
        assertEquals(4, reserved());
        assertEquals(5, db.queryForObject("SELECT max_calls FROM commerce_batch_model_limits WHERE execution_id='run'", Integer.class));
        assertEquals(2, count("commerce_batch_model_reservations"));
        assertEquals("QUEUED", run().get("status"));
        assertEquals("MOCKUP", run().get("stage"));
        assertNull(run().get("lease_token"));
        assertNull(run().get("lease_until"));
        assertEquals(1, count("commerce_batch_execution_events"));
        String event = db.queryForObject("SELECT message FROM commerce_batch_execution_events", String.class);
        assertTrue(event.contains("old"));
        assertTrue(event.contains(result.artifactId()));

        var artwork = new CommerceBatchArtworkService(db, null, null, null, null, null, null, null, null,
                access, new ObjectMapper(), null);
        var visible = artwork.list("batch", user);
        assertEquals(4, visible.size());
        assertFalse(visible.stream().anyMatch(row -> row.id().equals("old")));
        assertTrue(visible.stream().anyMatch(row -> row.id().equals(result.artifactId()) && row.status().equals("PREPARED")));
        assertTrue(visible.stream().anyMatch(row -> row.id().equals("ready") && row.check() != null));
        assertTrue(visible.stream().anyMatch(row -> row.id().equals("repair") && row.status().equals("READY")));
        assertTrue(visible.stream().anyMatch(row -> row.id().equals(result.artifactId()+"-guide") && "蓝色".equals(row.colorLabel())));

        // The worker's reservation for this same new request does not consume another call.
        var key = (String) fresh.get("request_key");
        assertTrue(Boolean.TRUE.equals(new TransactionTemplate(transactions).execute(status ->
                new CommerceBatchModelLimitService(db).reserve("run", "org", key, "MOCKUP"))));
        assertEquals(4, reserved());
    }

    @Test void concurrentRetryWaitsForExecutionLockThenReplaysExactlyOneNewJob() throws Exception {
        var service = service(db);
        var held = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                var result = service.retry("batch", "old", user);
                held.countDown();
                try { assertTrue(commit.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return result;
            }));
            assertTrue(held.await(10, TimeUnit.SECONDS));
            var second = pool.submit(() -> {
                secondStarted.countDown();
                return service.retry("batch", "old", user);
            });
            try {
                assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> second.get(250, TimeUnit.MILLISECONDS), "retry must wait for the execution row lock");
            } finally { commit.countDown(); }
            assertEquals(first.get(10, TimeUnit.SECONDS).artifactId(), second.get(10, TimeUnit.SECONDS).artifactId());
        } finally { commit.countDown(); }
        assertEquals(3, count("commerce_batch_artwork_jobs"));
        assertEquals(4, reserved());
        assertEquals(2, count("commerce_batch_model_reservations"));
        assertEquals(1, count("commerce_batch_execution_events"));
    }

    @Test void transactionFailureRollsBackReservationCloneRetirementAndRequeueTogether() {
        var failingDb = spy(db);
        doThrow(new IllegalStateException("event storage unavailable")).when(failingDb)
                .update(startsWith("INSERT INTO commerce_batch_execution_events"), anyString(), anyString(), anyString(), anyString(), any());
        assertThrows(IllegalStateException.class, () -> service(failingDb).retry("batch", "old", user));
        assertEquals("FAILED", run().get("status"));
        assertEquals("REJECTED", job("old").get("status"));
        assertEquals(2, count("commerce_batch_artwork_jobs"));
        assertEquals(3, reserved());
        assertEquals(1, count("commerce_batch_model_reservations"));
        assertEquals(0, count("commerce_batch_execution_events"));
    }

    @Test void foreignTenantOrJobAndUnresolvedSubmissionsNeverConsumeBudget() {
        var other = CommerceBatchArtworkRetryServiceTest.user("other-org", "u", List.of("product:edit", "product:publish"));
        var apparentRun = CommerceBatchArtworkRetryServiceTest.run("FAILED", "u");
        when(access.get("batch", other)).thenReturn(new com.yingdao.aicommerce.product.domain.CommerceBatchExecution(
                apparentRun.id(), "other-org", apparentRun.batchId(), apparentRun.createdBy(), apparentRun.stage(), apparentRun.status(),
                apparentRun.message(), null, null, apparentRun.createdAt(), apparentRun.updatedAt()));
        assertThrows(BusinessException.class, () -> service(db).retry("batch", "old", other));
        db.update("UPDATE commerce_batch_artwork_jobs SET organization_id='other-org' WHERE id='old'");
        assertThrows(BusinessException.class, () -> service(db).retry("batch", "old", user));
        db.update("UPDATE commerce_batch_artwork_jobs SET organization_id='org' WHERE id='old'");
        for (String state : List.of("SUBMITTING", "UNKNOWN", "RESULT_RECEIVED")) {
            db.update("UPDATE commerce_batch_artwork_jobs SET status=? WHERE id='ready'", state);
            assertThrows(BusinessException.class, () -> service(db).retry("batch", "old", user));
        }
        assertEquals(3, reserved());
        assertEquals("FAILED", run().get("status"));
        assertEquals("REJECTED", job("old").get("status"));
        assertEquals(2, count("commerce_batch_artwork_jobs"));
    }

    @Test void originalRetryReplayCannotRequeueOrAllocateAfterTheReplacementFinishesOrFails() {
        var service = service(db);
        var first = service.retry("batch", "old", user);
        for (String status : List.of("RUNNING", "COMPLETED", "FAILED", "UNKNOWN")) {
            db.update("UPDATE commerce_batch_executions SET status=?,message='worker progressed' WHERE id='run'", status);
            db.update("UPDATE commerce_batch_artwork_jobs SET status='REJECTED' WHERE id=?", first.artifactId());
            var replay = service.retry("batch", "old", user);
            assertEquals(first.artifactId(), replay.artifactId());
            assertEquals(status, replay.execution().status());
            assertEquals("worker progressed", run().get("message"));
        }
        assertEquals(4, reserved());
        assertEquals(3, count("commerce_batch_artwork_jobs"));
        assertEquals(1, count("commerce_batch_execution_events"));
    }

    @Test void retryAtTheOriginalLimitFailsWithoutRefundingTheRejectedAttempt() {
        db.update("UPDATE commerce_batch_model_limits SET max_calls=reserved_calls WHERE execution_id='run'");
        assertThrows(BusinessException.class, () -> service(db).retry("batch", "old", user));
        assertEquals("REJECTED", job("old").get("status"));
        assertEquals("FAILED", run().get("status"));
        assertEquals(3, reserved());
        assertEquals(3, db.queryForObject("SELECT max_calls FROM commerce_batch_model_limits WHERE execution_id='run'", Integer.class));
        assertEquals(1, count("commerce_batch_model_reservations"));
        assertEquals(2, count("commerce_batch_artwork_jobs"));
    }

    @Test void automaticImg2RetryKeepsEveryOutputAndStopsAtThreeDurableAttempts() {
        var run=automaticFixture();var retry=automaticService();
        String originalResponse=(String)job("old").get("result_json");
        var second=retry.retry(run,Map.of("id","old","status","RESULT_RECEIVED"));
        assertTrue(second.queued());assertEquals(2,second.attempt());assertEquals(2,reserved());
        assertEquals("RETRIED",job("old").get("status"));assertEquals(originalResponse,job("old").get("result_json"));
        assertEquals("original/bad",job("old").get("storage_key"));
        assertEquals(second.jobId(),retry.retry(run,Map.of("id","old")).jobId());assertEquals(2,reserved());
        db.update("UPDATE commerce_batch_artwork_jobs SET status='TRANSPARENCY_REJECTED',storage_key='original/bad-2',content_sha256=?,result_json=? WHERE id=?","c".repeat(64),originalResponse,second.jobId());
        var third=retry.retry(run,Map.of("id",second.jobId()));
        assertTrue(third.queued());assertEquals(3,third.attempt());assertEquals(3,reserved());
        db.update("UPDATE commerce_batch_artwork_jobs SET status='TRANSPARENCY_REJECTED',storage_key='original/bad-3',content_sha256=?,result_json=? WHERE id=?","d".repeat(64),originalResponse,third.jobId());
        assertEquals("EXHAUSTED",retry.retry(run,Map.of("id",third.jobId())).status());
        assertEquals(3,reserved());assertEquals(3,count("commerce_batch_model_reservations"));
        assertEquals(4,count("commerce_batch_artwork_jobs")); // Three attempts plus the untouched ready extraction.
        assertEquals("READY",job("ready").get("status"));assertEquals("RUNNING",run().get("status"));
    }

    @Test void racingWorkersCreateOnlyOneNextImg2AttemptAndOneReservation() throws Exception {
        var run=automaticFixture();var retry=automaticService();var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var a=executor.submit(()->{start.await();return retry.retry(run,Map.of("id","old"));});
            var b=executor.submit(()->{start.await();return retry.retry(run,Map.of("id","old"));});
            start.countDown();var one=a.get(10,TimeUnit.SECONDS);var two=b.get(10,TimeUnit.SECONDS);
            assertTrue(one.queued());assertTrue(two.queued());assertEquals(one.jobId(),two.jobId());
        }
        assertEquals(2,reserved());assertEquals(2,count("commerce_batch_model_reservations"));
        assertEquals(3,count("commerce_batch_artwork_jobs"));
    }

    private com.yingdao.aicommerce.product.domain.CommerceBatchExecution automaticFixture(){
        String key=CommerceTransparentArtworkRetry.rootKey("run","capture","VARIATE_G1",1,null);
        db.update("UPDATE commerce_batch_executions SET stage='VARIATE',status='RUNNING',lease_token='live',lease_until=TIMESTAMPADD(MINUTE,5,NOW(6)) WHERE id='run'");
        db.update("UPDATE commerce_batch_artwork_jobs SET kind='VARIATE_G1',variant_index=1,request_key=?,status='TRANSPARENCY_REJECTED',storage_key='original/bad',content_sha256=?,result_json=? WHERE id='old'",key,"b".repeat(64),"{\"artifact\":{\"dataBase64\":\"saved-opaque-response\"}}");
        db.update("DELETE FROM commerce_batch_model_reservations");
        db.update("INSERT INTO commerce_batch_model_reservations(execution_id,request_key,stage,created_at) VALUES('run',?,'VARIATE',NOW(6))",key);
        db.update("UPDATE commerce_batch_model_limits SET max_calls=15,reserved_calls=1 WHERE execution_id='run'");
        var now=java.time.Instant.now();
        return new com.yingdao.aicommerce.product.domain.CommerceBatchExecution("run","org","batch","u","VARIATE","RUNNING","","live",now.plusSeconds(300),now,now);
    }

    private CommerceTransparentArtworkRetry automaticService(){
        var target=new CommerceTransparentArtworkRetry(db,new CommerceBatchModelLimitService(db),new ObjectMapper());
        var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        return (CommerceTransparentArtworkRetry)proxy.getProxy();
    }

    private CommerceBatchArtworkRetryService service(JdbcTemplate template) {
        var target = new CommerceBatchArtworkRetryService(template, access, new CommerceBatchModelLimitService(template));
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (CommerceBatchArtworkRetryService) proxy.getProxy();
    }

    private Map<String, Object> job(String id) { return db.queryForMap("SELECT * FROM commerce_batch_artwork_jobs WHERE id=?", id); }
    private Map<String, Object> run() { return db.queryForMap("SELECT * FROM commerce_batch_executions WHERE id='run'"); }
    private int reserved() { return db.queryForObject("SELECT reserved_calls FROM commerce_batch_model_limits WHERE execution_id='run'", Integer.class); }
    private int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
}
