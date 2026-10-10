package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommerceDrapeMasterRetryTest {
    static final String TENANT = CommerceImageArchive.sha("org".getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "/";
    static final String GUIDE = TENANT + "c".repeat(64), SCENE = TENANT + "d".repeat(64), ART = TENANT + "e".repeat(64);
    static final String REFS = "[\"" + GUIDE + "\",\"" + SCENE + "\",\"" + ART + "\"]";
    final JdbcTemplate db = mock(JdbcTemplate.class);
    final CommerceBatchModelLimitService limits = mock(CommerceBatchModelLimitService.class);
    final ObjectMapper json = new ObjectMapper();
    final CommerceDrapeMasterRetry service = new CommerceDrapeMasterRetry(db, limits, json);
    final CommerceBatchExecution run = run("MOCKUP");
    Map<String,Object> master;
    List<Map<String,Object>> jobs;

    @BeforeEach void setup() {
        master = master("master-1", "root-key", "READY");
        jobs = new ArrayList<>(List.of(master, Map.of("id", "guide", "kind", "DRAPE_GUIDE", "status", "READY",
                "capture_id", "capture", "capture_version", 1L, "storage_key", GUIDE,
                "reference_keys", "[\"template\",\"uv\",\""+ART+"\"]")));
        for (int i=0; i<20; i++) jobs.add(Map.of("id", "mockup-"+i, "kind", "MOCKUP_FRONT", "status", "READY"));
        lease("MOCKUP", "RUNNING"); lock(master);
        when(limits.reserve(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        when(db.update(anyString(), any(Object[].class))).thenReturn(1);
    }

    @Test void definiteStyleFailureCreatesOnlyOneBoundedCorrectionAndPreservesAllOtherArtifacts() throws Exception {
        var result = service.retry(run, Map.of("id", "master-1", "status", "PREPARED"), failed());
        assertTrue(result.queued()); assertEquals("QUEUED", result.status()); assertEquals(2, result.attempt());
        assertDoesNotThrow(() -> UUID.fromString(result.artifactId()));
        var order = inOrder(db, limits);
        order.verify(db).queryForList(CommerceDrapeMasterRetry.LEASE_LOCK, "run", "org", "owner", "lease");
        order.verify(db).queryForList(CommerceDrapeMasterRetry.JOB_LOCK, "master-1", "run", "org");
        order.verify(db).queryForList(CommerceDrapeMasterRetry.ALL_JOBS_LOCK, "run", "org");
        order.verify(db).queryForList(CommerceDrapeMasterRetry.PUBLICATIONS_LOCK, "run", "org");
        order.verify(limits).reserve("run", "org", CommerceDrapeMasterRetry.nextKey("root-key"), "MOCKUP");
        order.verify(db).update(startsWith("INSERT INTO commerce_batch_artwork_jobs"), eq(result.artifactId()),
                eq(CommerceDrapeMasterRetry.nextKey("root-key")), argThat((String prompt) ->
                        prompt.startsWith(CommerceDrapeReuseWorkflow.PROMPT + CommerceDrapeMasterRetry.PROMPT_MARKER)
                                && prompt.contains("hasScene") && prompt.contains("白底平铺") && prompt.length() < CommerceDrapeReuseWorkflow.PROMPT.length()+1600),
                eq(REFS), eq(result.message()), eq("master-1"), eq("run"), eq("org"));
        order.verify(db).update(startsWith("UPDATE commerce_batch_artwork_jobs SET status='RETRIED'"), eq("master-1"), eq("run"), eq("org"));
        verify(db, times(2)).update(anyString(), any(Object[].class));
        verify(db, never()).update(startsWith("UPDATE commerce_batch_executions"), any(Object[].class));
        verify(limits, never()).initialize(anyString(), anyString(), anyInt());
        assertNotNull(CommerceDrapeMasterRetry.class.getMethod("retry", CommerceBatchExecution.class, Map.class, Map.class).getAnnotation(Transactional.class));
    }

    @Test void completePassingReviewAndUnknownReviewsCannotSpendAnotherCall() {
        assertEquals("PASSED", service.retry(run, master, passed()).status());
        assertEquals("REVIEW_INVALID", service.retry(run, master, null).status());
        assertEquals("REVIEW_INVALID", service.retry(run, master, Map.of("reason", "network error")).status());
        for (String flag : CommerceDrapeMasterRetry.FLAGS) {
            var missing = failed(); missing.remove(flag);
            assertEquals("REVIEW_INVALID", service.retry(run, master, missing).status());
            for (Object value : List.of("true", "false", "UNKNOWN", 0, 1)) {
                var wrong = failed(); wrong.put(flag, value);
                assertEquals("REVIEW_INVALID", service.retry(run, master, wrong).status());
            }
        }
        for (String reason : List.of("", "  ", "长".repeat(301))) {
            var wrong = failed(); wrong.put("reason", reason);
            assertEquals("REVIEW_INVALID", service.retry(run, master, wrong).status());
        }
        verifyNoInteractions(db, limits);
    }

    @Test void eachExplicitFailedBooleanCanRequestCorrectionWithoutRequiringAllSixToFail() {
        for (String flag : CommerceDrapeMasterRetry.FLAGS) {
            var review = passed(); review.put(flag, false);
            assertTrue(service.retry(run, master, review).queued(), flag);
        }
    }
    @Test void necklineOnlyFailureCreatesAVersionBoundCorrection() {
        master.put("prompt",CommerceDrapeReuseWorkflow.PROMPT_NECKLINE);
        var review=passed();
        review.put("contractVersion",CommerceDrapeStyleReview.NECKLINE_CONTRACT_VERSION);
        review.put("preservesNeckline",false);
        review.put("preservesSleeveBadge",true);
        review.put("preservesSideLabel",true);
        review.put("reason","领口开口变深，领边与模板不符");
        var result=service.retry(run,master,review);
        assertEquals("QUEUED",result.status());
        verify(limits).reserve("run","org",CommerceDrapeMasterRetry.nextKey("root-key",true),"MOCKUP");
        verify(db).update(startsWith("INSERT INTO commerce_batch_artwork_jobs"),eq(result.artifactId()),
                eq(CommerceDrapeMasterRetry.nextKey("root-key",true)),
                argThat((String prompt)->prompt.startsWith(CommerceDrapeReuseWorkflow.PROMPT_DETAIL_V4
                        +CommerceDrapeMasterRetry.PROMPT_MARKER)&&prompt.contains("preservesNeckline")),
                eq(REFS),eq(result.message()),eq("master-1"),eq("run"),eq("org"));
    }

    @Test void fiveMothersAreBoundedAndManualHistoryCannotResetCount() {
        master.put("status", "RETRIED");
        var second = master("master-2", "historical-manual-key", "READY"); jobs.add(second); lock(second);
        var result = service.retry(run, second, failed());
        assertEquals(3, result.attempt());
        verify(limits).reserve("run", "org", CommerceDrapeMasterRetry.nextKey("historical-manual-key"), "MOCKUP");
        clearInvocations(limits, db);
        second.put("status", "RETRIED");
        var third = master("master-3", CommerceDrapeMasterRetry.nextKey("historical-manual-key"), "READY");
        jobs.add(third); lock(third);
        assertEquals(4, service.retry(run, third, failed()).attempt());
        clearInvocations(limits, db);
        third.put("status", "RETRIED");
        var fourth = master("master-4", CommerceDrapeMasterRetry.nextKey((String)third.get("request_key")), "READY");
        jobs.add(fourth); lock(fourth);
        assertEquals(5, service.retry(run, fourth, failed()).attempt());
        clearInvocations(limits, db);
        fourth.put("status", "RETRIED");
        var fifth = master("master-5", CommerceDrapeMasterRetry.nextKey((String)fourth.get("request_key")), "READY");
        jobs.add(fifth); lock(fifth);
        var stopped = service.retry(run, fifth, failed());
        assertEquals("EXHAUSTED", stopped.status()); assertEquals(5, stopped.attempt()); assertFalse(stopped.queued());
        noWrites();
    }

    @Test void repeatedRetryOfRetiredMotherReusesTheSameSuccessorWithoutRequeueOrReservation() {
        master.put("status", "RETRIED");
        var next = master("master-2", CommerceDrapeMasterRetry.nextKey("root-key"), "PREPARED"); jobs.add(next);
        for (String status : List.of("PREPARED", "RESULT_RECEIVED", "READY", "RETRIED")) {
            next.put("status", status);
            var result = service.retry(run, master, failed());
            assertTrue(result.queued()); assertEquals("REUSED", result.status());
            assertEquals("master-2", result.artifactId()); assertEquals(2, result.attempt());
        }
        noWrites();
    }

    @Test void missingOrDuplicatedSuccessorsAndParallelActiveMothersDoNotCreateCalls() {
        master.put("status", "RETRIED");
        assertEquals("CHAIN_INVALID", service.retry(run, master, failed()).status());
        jobs.add(master("duplicate-1", CommerceDrapeMasterRetry.nextKey("root-key"), "READY"));
        jobs.add(master("duplicate-2", CommerceDrapeMasterRetry.nextKey("root-key"), "READY"));
        assertEquals("CHAIN_INVALID", service.retry(run, master, failed()).status());
        master.put("status", "READY");
        assertEquals("CHAIN_INVALID", service.retry(run, master, failed()).status());
        noWrites();
    }

    @Test void leaseLossPauseAndWrongStageNeverReplaceAnything() {
        when(db.queryForList(CommerceDrapeMasterRetry.LEASE_LOCK, "run", "org", "owner", "lease")).thenReturn(List.of());
        assertEquals("LEASE_LOST", service.retry(run, master, failed()).status());
        lease("MOCKUP", "PAUSING"); assertEquals("PAUSE_REQUESTED", service.retry(run, master, failed()).status());
        for (String stage : List.of("EXTRACT", "VARIATE", "REVIEW", "PUBLISH")) {
            lease(stage, "RUNNING"); assertEquals("NOT_RETRYABLE", service.retry(run(stage), master, failed()).status());
        }
        noWrites();
    }

    @Test void publicationAndAnyExistingDrapeDerivativesPreventReplacement() {
        when(db.queryForList(CommerceDrapeMasterRetry.PUBLICATIONS_LOCK, "run", "org")).thenReturn(List.of(Map.of("item_id", "fixed")));
        assertEquals("PUBLICATION_EXISTS", service.retry(run, master, failed()).status());
        when(db.queryForList(CommerceDrapeMasterRetry.PUBLICATIONS_LOCK, "run", "org")).thenReturn(List.of());
        for (String kind : List.of("DRAPE_BLANK", "DRAPED_FRONT")) {
            var derived = Map.<String,Object>of("kind", kind, "status", "READY", "capture_id", "capture"); jobs.add(derived);
            assertEquals("DERIVATIVES_EXIST", service.retry(run, master, failed()).status()); jobs.remove(derived);
        }
        noWrites();
    }

    @Test void unresolvedOrUnarchivedMotherAndOtherPendingImagesNeverTriggerGeneration() {
        for (String state : List.of("PREPARED", "PENDING", "QUEUED", "RUNNING", "SUBMITTING", "UNKNOWN", "RESULT_RECEIVED")) {
            master.put("status", state); assertEquals("UNRESOLVED", service.retry(run, master, failed()).status());
            master.put("status", "READY");
            var pending = Map.<String,Object>of("kind", "VARIATE", "status", state); jobs.add(pending);
            assertEquals("UNRESOLVED", service.retry(run, master, failed()).status()); jobs.remove(pending);
        }
        master.remove("storage_key"); assertEquals("NOT_RETRYABLE", service.retry(run, master, failed()).status());
        noWrites();
    }

    @Test void templateArtworkOrModelBindingMismatchBlocksAutomaticCorrection() {
        master.put("reference_keys", "[\"other-guide\",\"source-key\",\"art-key\"]");
        assertEquals("INPUTS_INVALID", service.retry(run, master, failed()).status());
        master.put("reference_keys", "[\"guide-key\",\"source-key\",\"other-art\"]");
        assertEquals("INPUTS_INVALID", service.retry(run, master, failed()).status());
        master.put("reference_keys", REFS);
        master.put("prompt", "unknown prompt"); assertEquals("INPUTS_INVALID", service.retry(run, master, failed()).status());
        master.put("prompt", CommerceDrapeReuseWorkflow.PROMPT); master.remove("model_fingerprint");
        assertEquals("INPUTS_INVALID", service.retry(run, master, failed()).status());
        noWrites();
    }

    @Test void onlyTheCurrentContractAndExactFrozenImageHashesCanAuthorizeCorrection() {
        var wrongContract = failed(); wrongContract.put("contractVersion", "another-version");
        assertEquals("REVIEW_INVALID", service.retry(run, master, wrongContract).status());
        for (String field : List.of("guideSha256", "sceneSha256", "masterSha256")) {
            var missing = failed(); missing.remove(field);
            assertEquals("REVIEW_INVALID", service.retry(run, master, missing).status());
            var anotherImage = failed(); anotherImage.put(field, "f".repeat(64));
            assertEquals("REVIEW_MISMATCH", service.retry(run, master, anotherImage).status());
        }
        master.put("storage_key", TENANT + "f".repeat(64));
        assertEquals("REVIEW_MISMATCH", service.retry(run, master, failed()).status());
        master.put("storage_key", "another-tenant/" + "a".repeat(64));
        assertEquals("REVIEW_MISMATCH", service.retry(run, master, failed()).status());
        noWrites();
    }

    @Test void knownLegacyReferenceOrderIsNormalizedWithoutChangingTheFrozenSourceBytes() {
        master.put("prompt", CommerceDrapeReuseWorkflow.LEGACY_PROMPT);
        master.put("reference_keys", "[\""+SCENE+"\",\""+GUIDE+"\",\""+ART+"\"]");
        var result = service.retry(run, master, failed());
        assertTrue(result.queued());
        verify(db).update(startsWith("INSERT INTO commerce_batch_artwork_jobs"), eq(result.artifactId()), anyString(),
                anyString(), eq(REFS), anyString(), eq("master-1"), eq("run"), eq("org"));
    }

    @Test void exhaustedOriginalBudgetDoesNotIncreaseBudgetOrRetireCurrentMother() {
        when(limits.reserve(anyString(), anyString(), anyString(), anyString())).thenReturn(false);
        assertEquals("LIMIT_REACHED", service.retry(run, master, failed()).status());
        verify(db, never()).update(anyString(), any(Object[].class));
        verify(limits, never()).initialize(anyString(), anyString(), anyInt());
    }

    @Test void insertAndRetirementFailureThrowWithinTransactionInsteadOfLeavingHalfAReplacement() {
        when(db.update(startsWith("INSERT INTO commerce_batch_artwork_jobs"), any(Object[].class))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.retry(run, master, failed()));
        when(db.update(startsWith("INSERT INTO commerce_batch_artwork_jobs"), any(Object[].class))).thenReturn(1);
        when(db.update(startsWith("UPDATE commerce_batch_artwork_jobs"), any(Object[].class))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.retry(run, master, failed()));
    }

    @Test void onlyVersionedBoundedPromptsAreRecognizedAsCorrectionMothers() {
        assertTrue(CommerceDrapeMasterRetry.supportedPrompt(CommerceDrapeReuseWorkflow.PROMPT));
        assertTrue(CommerceDrapeMasterRetry.supportedPrompt(CommerceDrapeReuseWorkflow.LEGACY_PROMPT));
        assertTrue(CommerceDrapeMasterRetry.supportedPrompt(CommerceDrapeReuseWorkflow.TEMPLATE_FIRST_PROMPT));
        assertFalse(CommerceDrapeMasterRetry.stylePrompt(CommerceDrapeReuseWorkflow.TEMPLATE_FIRST_PROMPT));
        assertFalse(CommerceDrapeMasterRetry.stylePrompt(CommerceDrapeReuseWorkflow.LEGACY_PROMPT));
        assertTrue(CommerceDrapeMasterRetry.stylePrompt(CommerceDrapeReuseWorkflow.PROMPT));
        assertTrue(CommerceDrapeMasterRetry.supportedPrompt(CommerceDrapeReuseWorkflow.PROMPT + CommerceDrapeMasterRetry.PROMPT_MARKER + "已核验说明"));
        assertTrue(CommerceDrapeMasterRetry.stylePrompt(CommerceDrapeReuseWorkflow.PROMPT + CommerceDrapeMasterRetry.PROMPT_MARKER + "已核验说明"));
        assertFalse(CommerceDrapeMasterRetry.supportedPrompt(null));
        assertFalse(CommerceDrapeMasterRetry.supportedPrompt("unrelated" + CommerceDrapeMasterRetry.PROMPT_MARKER));
        assertFalse(CommerceDrapeMasterRetry.supportedPrompt(CommerceDrapeReuseWorkflow.PROMPT + CommerceDrapeMasterRetry.PROMPT_MARKER + "x".repeat(1601)));
    }

    private void noWrites() { verifyNoInteractions(limits); verify(db, never()).update(anyString(), any(Object[].class)); }
    private void lease(String stage, String status) {
        when(db.queryForList(CommerceDrapeMasterRetry.LEASE_LOCK, "run", "org", "owner", "lease"))
                .thenReturn(List.of(Map.of("id", "run", "stage", stage, "status", status)));
    }
    private void lock(Map<String,Object> job) {
        when(db.queryForList(CommerceDrapeMasterRetry.JOB_LOCK, job.get("id"), "run", "org")).thenReturn(List.of(job));
        when(db.queryForList(CommerceDrapeMasterRetry.ALL_JOBS_LOCK, "run", "org")).thenReturn(jobs);
    }
    private Map<String,Object> master(String id, String key, String state) {
        var job = new HashMap<String,Object>();
        job.put("id", id); job.put("request_key", key); job.put("status", state); job.put("kind", "DRAPE_MASTER");
        job.put("capture_id", "capture"); job.put("capture_version", 1L); job.put("variant_index", 0);
        job.put("storage_key", TENANT + "a".repeat(64)); job.put("content_sha256", "a".repeat(64));
        job.put("prompt", CommerceDrapeReuseWorkflow.PROMPT); job.put("reference_keys", REFS);
        job.put("model_config_id", "frozen-model"); job.put("model_fingerprint", "b".repeat(64));
        return job;
    }
    private Map<String,Object> passed() {
        var review = new HashMap<String,Object>(); CommerceDrapeMasterRetry.FLAGS.forEach(flag -> review.put(flag, true));
        review.put("reason", "版型和胸花正确，但白底平铺没有承托和自然垂褶");
        review.put("contractVersion", CommerceDrapeMasterRetry.VERSION);
        review.put("guideSha256", "c".repeat(64)); review.put("sceneSha256", "d".repeat(64)); review.put("masterSha256", "a".repeat(64));
        return review;
    }
    private Map<String,Object> failed() { var review = passed(); review.put("hasScene", false); review.put("hasNaturalDrape", false); return review; }
    private static CommerceBatchExecution run(String stage) {
        Instant now = Instant.now();
        return new CommerceBatchExecution("run", "org", "batch", "owner", stage, "RUNNING", "", "lease", now.plusSeconds(300), now, now);
    }
}
