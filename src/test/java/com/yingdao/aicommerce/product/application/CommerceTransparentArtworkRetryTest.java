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

class CommerceTransparentArtworkRetryTest {
    final JdbcTemplate db = mock(JdbcTemplate.class);
    final CommerceBatchModelLimitService limits = mock(CommerceBatchModelLimitService.class);
    final ObjectMapper json = new ObjectMapper();
    final CommerceTransparentArtworkRetry service = new CommerceTransparentArtworkRetry(db, limits, json);
    final CommerceBatchExecution run = run("VARIATE");
    final String root = CommerceTransparentArtworkRetry.rootKey("run", "capture", "VARIATE_G2", 2, null);
    Map<String, Object> original;

    @BeforeEach void setup() {
        original = job(1, "REJECTED");
        lease("VARIATE", "RUNNING");
        locked(original, List.of(original));
        when(limits.reserve(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        when(db.update(anyString(), any(Object[].class))).thenReturn(1);
    }

    @Test void retryRereadsTheRejectedRowThenReservesANewKeyAndPreservesTheOldResponse() throws Exception {
        var result = service.retry(run, Map.of("id", "job-1", "status", "PREPARED"));
        assertEquals("QUEUED", result.status()); assertTrue(result.queued()); assertEquals(2, result.attempt());
        assertDoesNotThrow(() -> UUID.fromString(result.jobId()));
        String key = CommerceTransparentArtworkRetry.attemptKey(root, 2);
        assertNotEquals(root, key);
        var order = inOrder(db, limits);
        order.verify(db).queryForList(CommerceTransparentArtworkRetry.LEASE_LOCK, "run", "org", "user", "lease");
        order.verify(db).queryForList(CommerceTransparentArtworkRetry.JOB_LOCK, "job-1", "run", "org");
        order.verify(db).queryForList(CommerceTransparentArtworkRetry.CHAIN_LOCK, "run", "org", "capture", 1L, "VARIATE_G2", 2, null);
        order.verify(db).queryForList(CommerceTransparentArtworkRetry.PENDING_LOCK, "run", "org");
        order.verify(limits).reserve("run", "org", key, "VARIATE");
        order.verify(db).update(startsWith("INSERT INTO commerce_batch_artwork_jobs"), eq(result.jobId()), eq(key),
                eq(result.message()), eq("job-1"), eq("run"), eq("org"), eq("REJECTED"));
        order.verify(db).update(eq("UPDATE commerce_batch_artwork_jobs SET status='RETRIED',updated_at=NOW(6) WHERE id=? AND execution_id=? AND organization_id=? AND status=?"),
                eq("job-1"), eq("run"), eq("org"), eq("REJECTED"));
        verify(db, never()).update(startsWith("UPDATE commerce_batch_executions"), any(Object[].class));
        verify(limits, never()).initialize(anyString(), anyString(), anyInt());
        assertNotNull(CommerceTransparentArtworkRetry.class.getMethod("retry", CommerceBatchExecution.class, Map.class).getAnnotation(Transactional.class));
    }

    @Test void repeatedOriginalKeyReturnsPersistedSuccessorWithoutAnotherReservation() {
        original.put("status", "RETRIED");
        var second = job(2, "READY");
        locked(original, List.of(original, second));
        var result = service.retry(run, Map.of("id", "job-1"));
        assertEquals("REUSED", result.status()); assertTrue(result.queued());
        assertEquals("job-2", result.jobId()); assertEquals(2, result.attempt());
        noNewCall();
    }

    @Test void retryFromTheSecondAttemptUsesAttemptThreeAndThenStopsPermanently() {
        original.put("status", "RETRIED");
        var second = job(2, "REJECTED");
        locked(second, List.of(original, second));
        var next = service.retry(run, Map.of("id", "job-2"));
        assertTrue(next.queued()); assertEquals(3, next.attempt());
        verify(limits).reserve("run", "org", CommerceTransparentArtworkRetry.attemptKey(root, 3), "VARIATE");

        reset(db, limits);
        lease("VARIATE", "RUNNING");
        second.put("status", "RETRIED");
        var third = job(3, "REJECTED");
        locked(third, List.of(original, second, third));
        var stopped = service.retry(run, Map.of("id", "job-3"));
        assertEquals("EXHAUSTED", stopped.status()); assertFalse(stopped.queued());
        assertTrue(stopped.message().contains("3 次")); noNewCall();
    }

    @Test void archivedTransparencyFailureIsRetryableButAnUnarchivedOneIsNot() {
        original.put("status", "TRANSPARENCY_REJECTED");
        assertEquals("UNRESOLVED", service.retry(run, original).status());
        noNewCall();
        original.put("storage_key", "tenant/archive"); original.put("content_sha256", "a".repeat(64));
        var result = service.retry(run, original);
        assertTrue(result.queued());
        verify(limits).reserve("run", "org", CommerceTransparentArtworkRetry.attemptKey(root, 2), "VARIATE");
    }

    @Test void allUnresolvedSubmissionStatesCannotCreateANewAttempt() {
        for (String status : List.of("SUBMITTING", "UNKNOWN", "RESULT_RECEIVED")) {
            original.put("status", status);
            assertEquals("UNRESOLVED", service.retry(run, original).status(), status);
        }
        noNewCall();
    }

    @Test void definitivelyInvalidImageRequiresAPreservedResponseAndUsesTheSameBoundedChain() {
        original.put("status", "INVALID_IMAGE_REJECTED");
        original.put("result_json", "   ");
        assertEquals("UNRESOLVED", service.retry(run, original).status());
        noNewCall();
        original.put("result_json", "{\"requestId\":\"finished-request\",\"result\":{}}");
        var result = service.retry(run, original);
        assertTrue(result.queued()); assertEquals(2, result.attempt());
        verify(limits).reserve("run", "org", CommerceTransparentArtworkRetry.attemptKey(root, 2), "VARIATE");
        verify(db).update(startsWith("UPDATE commerce_batch_artwork_jobs SET status='RETRIED'"),
                eq("job-1"), eq("run"), eq("org"), eq("INVALID_IMAGE_REJECTED"));
    }

    @Test void anotherUnresolvedResultBlocksReservationEvenIfTheCurrentAttemptWasRejected() {
        for (String status : List.of("SUBMITTING", "UNKNOWN", "RESULT_RECEIVED")) {
            when(db.queryForList(CommerceTransparentArtworkRetry.PENDING_LOCK, "run", "org"))
                    .thenReturn(List.of(Map.of("id", "other", "status", status)));
            assertEquals("UNRESOLVED", service.retry(run, original).status());
        }
        noNewCall();
    }

    @Test void policyCopyrightUnsupportedModelsAndAuthenticationAreNotRetried() {
        for (String kind : List.of("POLICY", "COPYRIGHT", "MODEL_UNSUPPORTED", "AUTHENTICATION", "INVALID_REQUEST")) {
            rejection(original, kind, 0);
            assertEquals("NOT_RETRYABLE", service.retry(run, original).status(), kind);
        }
        rejection(original, "PROVIDER", 401);
        assertEquals("NOT_RETRYABLE", service.retry(run, original).status());
        rejection(original, "PROVIDER", 0); original.put("message", "当前图片模型不支持原生透明 PNG，未调用生图");
        assertEquals("NOT_RETRYABLE", service.retry(run, original).status());
        noNewCall();
    }

    @Test void unspecifiedProviderErrorsAreNotAssumedTemporary() {
        for (int http : List.of(0, 200, 500)) {
            rejection(original, "PROVIDER", http);
            assertEquals("NOT_RETRYABLE", service.retry(run, original).status());
        }
        noNewCall();
    }

    @Test void aStatusAloneCannotReplaceDefinitiveProviderRejectionEvidence() {
        for (String detail : List.of("", "broken", "{}", "{\"requestState\":\"UNKNOWN\",\"failureKind\":\"PROVIDER\"}")) {
            original.put("result_json", detail);
            assertEquals("NOT_RETRYABLE", service.retry(run, original).status());
        }
        noNewCall();
    }

    @Test void oldManualBranchesAndIncompleteChainsCannotResetTheAttemptCounter() {
        var second = job(2, "REJECTED");
        locked(second, List.of(second));
        assertEquals("CHAIN_INVALID", service.retry(run, second).status());
        var manual = job(2, "REJECTED"); manual.put("request_key", "f".repeat(64));
        locked(manual, List.of(original, manual));
        assertEquals("CHAIN_INVALID", service.retry(run, manual).status());
        original.put("status", "RETRIED");
        locked(original, List.of(original));
        assertEquals("CHAIN_INVALID", service.retry(run, original).status());
        noNewCall();
    }

    @Test void aChangedFrozenModelOrPromptDoesNotQualifyAsTheSameAutomaticRetryChain() {
        original.put("status", "RETRIED");
        var second = job(2, "REJECTED"); second.put("model_fingerprint", "b".repeat(64));
        locked(second, List.of(original, second));
        assertEquals("CHAIN_INVALID", service.retry(run, second).status());
        noNewCall();
    }

    @Test void leaseLossPauseAndLaterPipelineStagesNeverMutateTheTasks() {
        when(db.queryForList(CommerceTransparentArtworkRetry.LEASE_LOCK, "run", "org", "user", "lease")).thenReturn(List.of());
        assertEquals("LEASE_LOST", service.retry(run, original).status());
        lease("VARIATE", "PAUSING");
        assertEquals("PAUSE_REQUESTED", service.retry(run, original).status());
        for (String stage : List.of("MOCKUP", "REVIEW", "PUBLISH")) {
            lease(stage, "RUNNING");
            assertEquals("NOT_RETRYABLE", service.retry(run(stage), original).status());
        }
        lease("EXTRACT", "RUNNING");
        assertEquals("NOT_RETRYABLE", service.retry(run("EXTRACT"), original).status());
        noNewCall();
    }

    @Test void originalBudgetExhaustionDoesNotChangeTheBudgetOrRetireTheFailedResult() {
        when(limits.reserve(anyString(), anyString(), anyString(), anyString())).thenReturn(false);
        assertEquals("LIMIT_REACHED", service.retry(run, original).status());
        verify(db, never()).update(anyString(), any(Object[].class));
        verify(limits, never()).initialize(anyString(), anyString(), anyInt());
    }

    @Test void failedInsertOrRetirementThrowsSoTheTransactionRollsBackTheReservation() {
        when(db.update(startsWith("INSERT INTO commerce_batch_artwork_jobs"), any(Object[].class))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.retry(run, original));
        when(db.update(startsWith("INSERT INTO commerce_batch_artwork_jobs"), any(Object[].class))).thenReturn(1);
        when(db.update(startsWith("UPDATE commerce_batch_artwork_jobs SET status='RETRIED'"), any(Object[].class))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.retry(run, original));
    }

    @Test void requestKeyIdentityMatchesTheExistingInitialRequestAndIncludesGroupAndColor() {
        assertEquals(CommerceBatchArtworkService.requestKey("run", "capture", "VARIATE_G2", 2, null), root);
        assertNotEquals(root, CommerceTransparentArtworkRetry.rootKey("run", "capture", "VARIATE_G1", 2, null));
        assertNotEquals(root, CommerceTransparentArtworkRetry.rootKey("run", "capture", "VARIATE_G2", 2, "BLACK"));
        assertNotEquals(CommerceTransparentArtworkRetry.attemptKey(root, 2), CommerceTransparentArtworkRetry.attemptKey(root, 3));
    }

    private void noNewCall() {
        verifyNoInteractions(limits);
        verify(db, never()).update(anyString(), any(Object[].class));
    }
    private void lease(String stage, String status) {
        when(db.queryForList(CommerceTransparentArtworkRetry.LEASE_LOCK, "run", "org", "user", "lease"))
                .thenReturn(List.of(Map.of("id", "run", "stage", stage, "status", status)));
    }
    private void locked(Map<String, Object> job, List<Map<String, Object>> chain) {
        when(db.queryForList(CommerceTransparentArtworkRetry.JOB_LOCK, job.get("id"), "run", "org")).thenReturn(List.of(job));
        when(db.queryForList(CommerceTransparentArtworkRetry.CHAIN_LOCK, "run", "org", "capture", 1L, "VARIATE_G2", 2, null)).thenReturn(chain);
    }
    private Map<String, Object> job(int attempt, String status) {
        var result = new HashMap<String, Object>();
        result.put("id", "job-" + attempt); result.put("status", status);
        result.put("request_key", CommerceTransparentArtworkRetry.attemptKey(root, attempt));
        result.put("capture_id", "capture"); result.put("capture_version", 1L);
        result.put("kind", "VARIATE_G2"); result.put("variant_index", 2);
        result.put("prompt", "fixed transparent prompt"); result.put("reference_keys", "[\"fixed-reference\"]");
        result.put("model_config_id", "fixed-img2"); result.put("model_fingerprint", "a".repeat(64));
        rejection(result, "CONCURRENCY", 429);
        return result;
    }
    private void rejection(Map<String, Object> job, String kind, int http) {
        job.put("result_json", json.writeValueAsString(Map.of("requestState", "REJECTED", "failureKind", kind, "providerStatus", http)));
    }
    private static CommerceBatchExecution run(String stage) {
        var now = Instant.now();
        return new CommerceBatchExecution("run", "org", "batch", "user", stage, "RUNNING", "", "lease", now.plusSeconds(90), now, now);
    }
}
