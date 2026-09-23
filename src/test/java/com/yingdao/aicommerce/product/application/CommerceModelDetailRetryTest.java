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

class CommerceModelDetailRetryTest {
    static final String PREFIX = CommerceImageArchive.sha("org".getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "/";
    final JdbcTemplate db = mock(JdbcTemplate.class);
    final CommerceBatchModelLimitService limits = mock(CommerceBatchModelLimitService.class);
    final ObjectMapper json = new ObjectMapper();
    final CommerceModelDetailRetry service = new CommerceModelDetailRetry(db, limits, json);
    final CommerceBatchExecution run = run("MOCKUP");
    Map<String,Object> job;
    List<Map<String,Object>> jobs;

    @BeforeEach void setup() {
        job = job("detail-1", "root-key", "READY");
        jobs = new ArrayList<>(List.of(job, Map.of("id", "main", "kind", "DRAPED_FRONT", "status", "READY"),
                Map.of("id", "blank", "kind", "DRAPE_BLANK", "status", "READY")));
        lease("MOCKUP", "RUNNING"); lock(job);
        when(limits.reserve(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        when(db.update(anyString(), any(Object[].class))).thenReturn(1);
        when(db.queryForList(CommerceModelDetailRetry.PLAN_LOCK, "run", "org")).thenReturn(List.of(Map.of("plan_json",
                "{\"variantsPerPrint\":2,\"sources\":[{\"captureId\":\"capture\",\"version\":7,\"detailImageIds\":[\"source-detail\",\"source-detail-2\"]}]}")));
    }

    @Test void boundVisualFailureCreatesOneCorrectionAndDoesNotReplaceMainOrOtherDetails() throws Exception {
        var result = service.retry(run, Map.of("id", "detail-1", "status", "PREPARED"), failed());
        assertEquals("QUEUED", result.status()); assertEquals(2, result.attempt()); assertTrue(result.queued());
        assertDoesNotThrow(() -> UUID.fromString(result.artifactId()));
        verify(limits).reserve("run", "org", CommerceModelDetailRetry.nextKey("root-key"), "MOCKUP");
        verify(db).update(startsWith("INSERT INTO commerce_batch_artwork_jobs"), eq(result.artifactId()), anyString(),
                argThat((String prompt) -> CommerceModelDetailPolicy.supportedPrompt(prompt, 1)
                        && prompt.contains("hasModel") && prompt.contains("不是真实穿着")),
                eq(result.message()), eq("detail-1"), eq("run"), eq("org"));
        verify(db).update(startsWith("UPDATE commerce_batch_artwork_jobs SET status='RETRIED'"), eq("detail-1"), eq("run"), eq("org"));
        verify(db, times(2)).update(anyString(), any(Object[].class));
        verify(limits, never()).initialize(anyString(), anyString(), anyInt());
        assertNotNull(CommerceModelDetailRetry.class.getMethod("retry", CommerceBatchExecution.class, Map.class, Map.class).getAnnotation(Transactional.class));
    }

    @Test void malformedOrUnavailableReviewNeverSpendsAndPassingReviewNeedsNoCorrection() {
        assertEquals("PASSED", service.retry(run, job, passed()).status());
        assertEquals("REVIEW_INVALID", service.retry(run, job, null).status());
        for (String flag : CommerceModelDetailReview.CHECKS) {
            var invalid = failed(); invalid.remove(flag);
            assertEquals("REVIEW_INVALID", service.retry(run, job, invalid).status());
            for (Object value : List.of("false", "UNKNOWN", 0)) {
                invalid = failed(); invalid.put(flag, value);
                assertEquals("REVIEW_INVALID", service.retry(run, job, invalid).status());
            }
        }
        for (String field : List.of("contractVersion", "sourceDetailImageId", "productSha256", "styleSha256", "outputSha256")) {
            var invalid = failed(); invalid.remove(field);
            assertEquals("REVIEW_INVALID", service.retry(run, job, invalid).status());
        }
        verifyNoInteractions(db, limits);
    }

    @Test void incorrectReviewHashesOrVariantOrdinalBindingsCannotRegenerateAnotherPicture() {
        for (String field : List.of("productSha256", "styleSha256", "outputSha256")) {
            var wrong = failed(); wrong.put(field, "f".repeat(64));
            assertEquals("REVIEW_MISMATCH", service.retry(run, job, wrong).status());
        }
        for (var mismatch : List.of(Map.entry("captureId", "another"), Map.entry("detailIndex", 2), Map.entry("variant", 2))) {
            var wrong = failed(); wrong.put(mismatch.getKey(), mismatch.getValue());
            assertEquals("REVIEW_MISMATCH", service.retry(run, job, wrong).status());
        }
        var wrong = failed(); wrong.put("detailIndex", 1.5);
        assertEquals("REVIEW_MISMATCH", service.retry(run, job, wrong).status());
        noWrites();
    }

    @Test void sourceDetailMustExistAtTheSameOrdinalInTheFrozenPlan() {
        var wrong = failed(); wrong.put("sourceDetailImageId", "not-frozen");
        assertEquals("INPUTS_INVALID", service.retry(run, job, wrong).status());
        when(db.queryForList(CommerceModelDetailRetry.PLAN_LOCK, "run", "org")).thenReturn(List.of());
        assertEquals("INPUTS_INVALID", service.retry(run, job, failed()).status());
        noWrites();
    }

    @Test void secondAttemptIsFinalAndReplayOfTheFirstReusesTheSamePreparedJob() {
        job.put("status", "RETRIED");
        var second = job("detail-2", CommerceModelDetailRetry.nextKey("root-key"), "PREPARED"); jobs.add(second);
        second.remove("storage_key"); second.remove("content_sha256");
        var replay = service.retry(run, job, failed());
        assertEquals("REUSED", replay.status()); assertEquals("detail-2", replay.artifactId()); assertEquals(2, replay.attempt());
        second.put("status", "READY"); second.put("storage_key", PREFIX+"c".repeat(64)); second.put("content_sha256", "c".repeat(64)); lock(second);
        assertEquals("EXHAUSTED", service.retry(run, second, failed()).status());
        noWrites();
    }

    @Test void attemptLimitIsScopedToVariantAndDetailOrdinalNotTheWholeCapture() {
        var otherVariant = job("other-variant", "other-v", "READY"); otherVariant.put("variant_index", 2); jobs.add(otherVariant);
        var otherDetail = job("other-detail", "other-d", "READY"); otherDetail.put("kind", "MODEL_DETAIL_2"); jobs.add(otherDetail);
        assertEquals(2, service.retry(run, job, failed()).attempt());
    }

    @Test void unresolvedCurrentOrOtherProviderRequestsCannotCausePaidReplay() {
        for (String state : List.of("PREPARED", "PENDING", "QUEUED", "RUNNING", "SUBMITTING", "UNKNOWN", "RESULT_RECEIVED")) {
            job.put("status", state); assertEquals("UNRESOLVED", service.retry(run, job, failed()).status()); job.put("status", "READY");
            var pending = Map.<String,Object>of("kind", "MODEL_DETAIL_3", "status", state); jobs.add(pending);
            assertEquals("UNRESOLVED", service.retry(run, job, failed()).status()); jobs.remove(pending);
        }
        noWrites();
    }

    @Test void staleLeasePauseAndPublishingCannotReplaceSavedDetailImages() {
        when(db.queryForList(CommerceModelDetailRetry.LEASE_LOCK, "run", "org", "owner", "lease")).thenReturn(List.of());
        assertEquals("LEASE_LOST", service.retry(run, job, failed()).status());
        lease("MOCKUP", "PAUSING"); assertEquals("PAUSE_REQUESTED", service.retry(run, job, failed()).status());
        lease("REVIEW", "RUNNING"); assertEquals("NOT_RETRYABLE", service.retry(run("REVIEW"), job, failed()).status());
        lease("MOCKUP", "RUNNING");
        when(db.queryForList(CommerceModelDetailRetry.PUBLICATIONS_LOCK, "run", "org")).thenReturn(List.of(Map.of("item_id", "frozen")));
        assertEquals("PUBLICATION_EXISTS", service.retry(run, job, failed()).status());
        noWrites();
    }

    @Test void missingArchivesWrongPromptVersionAndForeignArchivePathsCannotPassBinding() {
        job.put("storage_key", ""); assertEquals("NOT_RETRYABLE", service.retry(run, job, failed()).status());
        job.put("storage_key", "other-tenant/" + "c".repeat(64)); assertEquals("INPUTS_INVALID", service.retry(run, job, failed()).status());
        job.put("storage_key", PREFIX + "c".repeat(64)); job.put("prompt", CommerceModelDetailPolicy.prompt(2));
        assertEquals("REVIEW_MISMATCH", service.retry(run, job, failed()).status());
        noWrites();
    }

    @Test void missingSuccessorOrTwoActiveAttemptsStopInsteadOfResettingAttemptCount() {
        job.put("status", "RETRIED"); assertEquals("CHAIN_INVALID", service.retry(run, job, failed()).status());
        job.put("status", "READY"); jobs.add(job("duplicate", "manual-new-key", "READY"));
        assertEquals("CHAIN_INVALID", service.retry(run, job, failed()).status());
        noWrites();
    }

    @Test void originalBudgetRemainsAHardLimit() {
        when(limits.reserve(anyString(), anyString(), anyString(), anyString())).thenReturn(false);
        assertEquals("LIMIT_REACHED", service.retry(run, job, failed()).status());
        verify(db, never()).update(anyString(), any(Object[].class));
        verify(limits, never()).initialize(anyString(), anyString(), anyInt());
    }

    @Test void failedInsertOrRetirementThrowsWithinTransaction() {
        when(db.update(startsWith("INSERT INTO commerce_batch_artwork_jobs"), any(Object[].class))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.retry(run, job, failed()));
        when(db.update(startsWith("INSERT INTO commerce_batch_artwork_jobs"), any(Object[].class))).thenReturn(1);
        when(db.update(startsWith("UPDATE commerce_batch_artwork_jobs"), any(Object[].class))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.retry(run, job, failed()));
    }

    @Test void retiredNetworkFailureDoesNotConsumeTheFirstGridChildQualityCorrection() {
        var network = networkFailure("old-network"); jobs.add(network);
        job.put("result_json", "{\"algorithm\":\"model-detail-grid-v1\",\"parentJobId\":\"grid-parent\",\"slot\":0}");
        var result = service.retry(run, job, failed());
        assertEquals("QUEUED", result.status()); assertEquals(2, result.attempt());
        assertFalse(network.containsKey("storage_key")); assertFalse(network.containsKey("content_sha256"));
        assertEquals("RETRIED", network.get("status"));
        verify(limits).reserve("run", "org", CommerceModelDetailRetry.nextKey("root-key"), "MOCKUP");
        verify(limits, never()).initialize(anyString(), anyString(), anyInt());
        verify(db, times(2)).update(anyString(), any(Object[].class));
    }

    @Test void twoRealArchivedQualityAttemptsStillStopEvenWithAdditionalNetworkFailures() {
        jobs.add(networkFailure("old-network"));
        var earlier = job("earlier-image", "earlier-key", "RETRIED");
        earlier.put("storage_key", PREFIX+"d".repeat(64)); earlier.put("content_sha256", "d".repeat(64)); jobs.add(earlier);
        var result = service.retry(run, job, failed());
        assertEquals("EXHAUSTED", result.status()); assertEquals(2, result.attempt()); noWrites();
    }

    @Test void incompleteOrConflictingHistoricArchivesCannotSilentlyReduceQualityAttemptCount() {
        for (String mismatch : List.of("MISSING_HASH", "MISSING_KEY", "FOREIGN_KEY", "DIFFERENT_HASH", "INVALID_HASH", "INVALID_KEY")) {
            var earlier = job("historic-"+mismatch, "historic-key", "RETRIED");
            if (mismatch.equals("MISSING_HASH")) earlier.remove("content_sha256");
            if (mismatch.equals("MISSING_KEY")) earlier.remove("storage_key");
            if (mismatch.equals("FOREIGN_KEY")) earlier.put("storage_key", "other-tenant/"+"c".repeat(64));
            if (mismatch.equals("DIFFERENT_HASH")) earlier.put("content_sha256", "d".repeat(64));
            if (mismatch.equals("INVALID_HASH")) earlier.put("content_sha256", "invalid");
            if (mismatch.equals("INVALID_KEY")) earlier.put("storage_key", PREFIX+"invalid");
            jobs.add(earlier); assertEquals("CHAIN_INVALID", service.retry(run, job, failed()).status(), mismatch); jobs.remove(earlier);
        }
        noWrites();
    }

    @Test void pendingCorrectionReplayExcludesNetworkHistoryButKeepsItsReservedQualitySlot() {
        jobs.add(networkFailure("old-network")); job.put("status", "RETRIED");
        var second = job("detail-2", CommerceModelDetailRetry.nextKey("root-key"), "PREPARED");
        second.remove("storage_key"); second.remove("content_sha256"); jobs.add(second);
        var result = service.retry(run, job, failed());
        assertEquals("REUSED", result.status()); assertEquals("detail-2", result.artifactId()); assertEquals(2, result.attempt()); noWrites();
    }

    @Test void networkAttemptsRemainSubjectToTheUnchangedTotalCallBudget() {
        jobs.add(networkFailure("old-network-1")); jobs.add(networkFailure("old-network-2"));
        when(limits.reserve(anyString(), anyString(), anyString(), anyString())).thenReturn(false);
        var result = service.retry(run, job, failed());
        assertEquals("LIMIT_REACHED", result.status()); assertEquals(1, result.attempt());
        verify(limits).reserve("run", "org", CommerceModelDetailRetry.nextKey("root-key"), "MOCKUP");
        verify(limits, never()).initialize(anyString(), anyString(), anyInt()); verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void currentOutputHashMustMatchTheTenantBoundArchiveBeforeCountingAttempts() {
        job.put("storage_key", PREFIX+"d".repeat(64));
        assertEquals("REVIEW_MISMATCH", service.retry(run, job, failed()).status()); noWrites();
    }

    private Map<String,Object> networkFailure(String id) {
        var result = job(id, id+"-request", "RETRIED"); result.remove("storage_key"); result.remove("content_sha256");
        result.put("result_json", "{\"failureKind\":\"NETWORK\",\"providerStatus\":502}"); return result;
    }

    private void noWrites() { verifyNoInteractions(limits); verify(db, never()).update(anyString(), any(Object[].class)); }
    private void lease(String stage, String state) {
        when(db.queryForList(CommerceModelDetailRetry.LEASE_LOCK, "run", "org", "owner", "lease"))
                .thenReturn(List.of(Map.of("stage", stage, "status", state)));
    }
    private void lock(Map<String,Object> value) {
        when(db.queryForList(CommerceModelDetailRetry.JOB_LOCK, value.get("id"), "run", "org")).thenReturn(List.of(value));
        when(db.queryForList(CommerceModelDetailRetry.ALL_JOBS_LOCK, "run", "org")).thenReturn(jobs);
    }
    private Map<String,Object> job(String id, String key, String status) {
        var result = new HashMap<String,Object>();
        result.put("id", id); result.put("request_key", key); result.put("status", status); result.put("kind", "MODEL_DETAIL_1");
        result.put("capture_id", "capture"); result.put("capture_version", 7L); result.put("variant_index", 1);
        result.put("storage_key", PREFIX+"c".repeat(64)); result.put("content_sha256", "c".repeat(64));
        result.put("reference_keys", json.writeValueAsString(List.of(PREFIX+"a".repeat(64), PREFIX+"b".repeat(64))));
        result.put("prompt", CommerceModelDetailPolicy.prompt(1)); result.put("model_config_id", "frozen"); result.put("model_fingerprint", "model-sha");
        return result;
    }
    private Map<String,Object> passed() {
        var report = new HashMap<String,Object>(); CommerceModelDetailReview.CHECKS.forEach(flag -> report.put(flag, true));
        report.put("reason", "画面不是真实穿着，需要纠正衣物与身体的贴合关系");
        report.put("contractVersion", CommerceModelDetailReview.CONTRACT_VERSION); report.put("sourceDetailImageId", "source-detail");
        report.put("productSha256", "a".repeat(64)); report.put("styleSha256", "b".repeat(64)); report.put("outputSha256", "c".repeat(64));
        report.put("detailIndex", 1); report.put("variant", 1); report.put("captureId", "capture"); return report;
    }
    private Map<String,Object> failed() { var report = passed(); report.put("hasModel", false); return report; }
    private static CommerceBatchExecution run(String stage) {
        var now = Instant.now(); return new CommerceBatchExecution("run", "org", "batch", "owner", stage, "RUNNING", "", "lease", now.plusSeconds(300), now, now);
    }
}
