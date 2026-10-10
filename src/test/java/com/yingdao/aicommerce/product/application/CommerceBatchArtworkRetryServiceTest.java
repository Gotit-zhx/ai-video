package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.product.domain.CommerceBatchExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommerceBatchArtworkRetryServiceTest {
    static final String RUN_LOCK = "SELECT * FROM commerce_batch_executions WHERE id=? AND organization_id=? AND batch_id=? FOR UPDATE";
    static final String JOB_LOCK = "SELECT * FROM commerce_batch_artwork_jobs WHERE id=? AND execution_id=? AND organization_id=? FOR UPDATE";
    static final String PENDING_LOCK = "SELECT id,status FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=? AND status IN ('SUBMITTING','UNKNOWN','RESULT_RECEIVED') FOR UPDATE";
    static final String REPLACEMENT_LOCK = "SELECT id FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=? AND request_key=? FOR UPDATE";
    static final String PUBLICATION_LOCK = "SELECT item_id FROM commerce_batch_publications WHERE execution_id=? AND organization_id=? FOR UPDATE";
    static final String DRAPE_JOBS_LOCK = "SELECT * FROM commerce_batch_artwork_jobs WHERE execution_id=? AND organization_id=? AND status<>'RETRIED' FOR UPDATE";
    static final String KEY = CommerceImageArchive.sha("original-request:manual-retry".getBytes(StandardCharsets.UTF_8));
    final JdbcTemplate db = mock(JdbcTemplate.class);
    final CommerceBatchExecutionService executions = mock(CommerceBatchExecutionService.class);
    final CommerceBatchModelLimitService limits = mock(CommerceBatchModelLimitService.class);
    final CommerceBatchArtworkRetryService service = new CommerceBatchArtworkRetryService(db, executions, limits);
    final PlatformPrincipal user = user("org", "u", List.of("product:edit", "product:publish"));

    @BeforeEach void setup() {
        when(executions.get("batch", user)).thenReturn(run("RUNNING", "u"));
        lockedRun("FAILED", "u");
        job("REJECTED");
        when(limits.reserve("run", "org", KEY, "MOCKUP")).thenReturn(true);
        when(db.update(anyString(), any(Object[].class))).thenReturn(1);
    }

    @Test void permissionChecksPrecedeAnyBatchOrJobRead() {
        for (var candidate : List.of(user("org", "u", List.of()), user("org", "u", List.of("product:edit")),
                user("org", "u", List.of("product:publish")), user(null, "u", List.of("product:edit", "product:publish")))) {
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class,
                    () -> service.retry("batch", "old", candidate)).getStatus());
        }
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class,
                () -> service.retry("batch", "old", null)).getStatus());
        verifyNoInteractions(executions, db, limits);
    }

    @Test void preservesSourceAccessChecksAndRejectsAnotherStarter() {
        when(executions.get("batch", user)).thenThrow(new BusinessException(HttpStatus.FORBIDDEN, "source inaccessible"));
        assertThrows(BusinessException.class, () -> service.retry("batch", "old", user));
        verifyNoInteractions(db, limits);
        doReturn(run("FAILED", "u")).when(executions).get("batch", user);
        lockedRun("FAILED", "another-user");
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(BusinessException.class,
                () -> service.retry("batch", "old", user)).getStatus());
        verify(db, never()).queryForList(anyString(), any(Object[].class));
        verifyNoInteractions(limits);
    }

    @Test void missingTenantScopedExecutionAndJobCannotBeRetried() {
        when(db.query(eq(RUN_LOCK), org.mockito.ArgumentMatchers.<RowMapper<CommerceBatchExecution>>any(),
                eq("run"), eq("org"), eq("batch"))).thenReturn(List.of());
        assertThrows(BusinessException.class, () -> service.retry("batch", "old", user));
        lockedRun("FAILED", "u");
        when(db.queryForList(JOB_LOCK, "foreign-job", "run", "org")).thenReturn(List.of());
        assertThrows(BusinessException.class, () -> service.retry("batch", "foreign-job", user));
        verify(db).queryForList(JOB_LOCK, "foreign-job", "run", "org");
        verify(db, never()).update(anyString(), any(Object[].class));
        verifyNoInteractions(limits);
    }

    @Test void onlyFailedExecutionAndExplicitlyRejectedJobCanStartANewAttempt() {
        for (String status : List.of("QUEUED", "RUNNING", "UNKNOWN", "COMPLETED", "PAUSED", "CANCELLED")) {
            lockedRun(status, "u");
            assertThrows(BusinessException.class, () -> service.retry("batch", "old", user), status);
        }
        lockedRun("FAILED", "u");
        for (String status : List.of("PREPARED", "SUBMITTING", "UNKNOWN", "RESULT_RECEIVED", "READY")) {
            job(status);
            assertThrows(BusinessException.class, () -> service.retry("batch", "old", user), status);
        }
        verifyNoInteractions(limits);
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void anyUnresolvedOrUnarchivedResultBlocksRetryBeforeReservation() {
        for (String status : List.of("SUBMITTING", "UNKNOWN", "RESULT_RECEIVED")) {
            when(db.queryForList(PENDING_LOCK, "run", "org")).thenReturn(List.of(Map.of("id", "other", "status", status)));
            assertThrows(BusinessException.class, () -> service.retry("batch", "old", user), status);
        }
        verifyNoInteractions(limits);
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void exhaustedOriginalBudgetDoesNotRequeueOrModifyAnyJob() {
        when(limits.reserve("run", "org", KEY, "MOCKUP")).thenReturn(false);
        var error = assertThrows(BusinessException.class, () -> service.retry("batch", "old", user));
        assertTrue(error.getMessage().contains("调用上限"));
        verify(db, never()).update(anyString(), any(Object[].class));
        verify(limits, never()).initialize(anyString(), anyString(), anyInt());
    }

    @Test void invalidTransparentOutputCanBeRetriedOnlyWithOriginalBudgetBeforeMockups() {
        job("TRANSPARENCY_REJECTED");
        var before=run("FAILED","u");
        var variantRun=new CommerceBatchExecution(before.id(),before.organizationId(),before.batchId(),before.createdBy(),"VARIATE",before.status(),before.message(),before.leaseToken(),before.leaseUntil(),before.createdAt(),before.updatedAt());
        when(db.query(eq(RUN_LOCK),org.mockito.ArgumentMatchers.<RowMapper<CommerceBatchExecution>>any(),eq("run"),eq("org"),eq("batch"))).thenReturn(List.of(variantRun));
        when(limits.reserve("run","org",KEY,"VARIATE")).thenReturn(true);
        var retried=service.retry("batch","old",user);
        assertEquals("VARIATE",retried.execution().stage());assertEquals("QUEUED",retried.execution().status());
        verify(limits).reserve("run","org",KEY,"VARIATE");
        verify(limits,never()).initialize(anyString(),anyString(),anyInt());
    }

    @Test void oldInvalidInputCannotReplaceAlreadyComposedOutputsThroughSingleImageRetry() {
        job("TRANSPARENCY_REJECTED");
        var failure=assertThrows(BusinessException.class,()->service.retry("batch","old",user));
        assertTrue(failure.getMessage().contains("已进入套版阶段"));verifyNoInteractions(limits);
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void locksBeforeReservingAndRequeuesTheCurrentStageWithANewRuntimeIdentity() throws Exception {
        var result = service.retry("batch", "old", user);
        assertDoesNotThrow(() -> UUID.fromString(result.artifactId()));
        assertNotEquals("old", result.artifactId());
        assertEquals("MOCKUP", result.execution().stage());
        assertEquals("QUEUED", result.execution().status());
        assertNull(result.execution().leaseToken());
        assertNull(result.execution().leaseUntil());
        assertTrue(result.execution().message().contains("old"));
        assertTrue(result.execution().message().contains(result.artifactId()));
        var order = inOrder(executions, db, limits);
        order.verify(executions).get("batch", user);
        order.verify(db).query(eq(RUN_LOCK), org.mockito.ArgumentMatchers.<RowMapper<CommerceBatchExecution>>any(),
                eq("run"), eq("org"), eq("batch"));
        order.verify(db).queryForList(JOB_LOCK, "old", "run", "org");
        order.verify(db).queryForList(PENDING_LOCK, "run", "org");
        order.verify(limits).reserve("run", "org", KEY, "MOCKUP");
        order.verify(db).update(startsWith("INSERT INTO commerce_batch_artwork_jobs"), eq(result.artifactId()), eq(KEY),
                anyString(), eq("old"), eq("run"), eq("org"));
        order.verify(db).update(startsWith("UPDATE commerce_batch_artwork_jobs SET status='RETRIED'"), eq("old"), eq("run"), eq("org"));
        order.verify(db).update(startsWith("UPDATE commerce_batch_executions SET status='QUEUED'"), anyString(), any(), eq("run"), eq("org"));
        order.verify(db).update(startsWith("INSERT INTO commerce_batch_execution_events"), anyString(), eq("run"), eq("MOCKUP"),
                eq(result.execution().message()), any());
        assertNotNull(CommerceBatchArtworkRetryService.class.getMethod("retry", String.class, String.class,
                PlatformPrincipal.class).getAnnotation(Transactional.class));
    }

    @Test void replayReturnsPersistedReplacementEvenAfterWorkerAdvancedWithoutRequeueOrReservation() {
        job("RETRIED");
        when(db.queryForList(REPLACEMENT_LOCK, "run", "org", KEY)).thenReturn(List.of(Map.of("id", "replacement")));
        for (String status : List.of("QUEUED", "RUNNING", "UNKNOWN", "COMPLETED", "FAILED")) {
            lockedRun(status, "u");
            var replay = service.retry("batch", "old", user);
            assertEquals("replacement", replay.artifactId());
            assertEquals(status, replay.execution().status());
        }
        verifyNoInteractions(limits);
        verify(db, never()).update(anyString(), any(Object[].class));
        verify(db, never()).queryForList(PENDING_LOCK, "run", "org");
    }

    @Test void brokenRetryLinkNeverCreatesAnotherAttempt() {
        job("RETRIED");
        assertThrows(BusinessException.class, () -> service.retry("batch", "old", user));
        verifyNoInteractions(limits);
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void explicitLegacyMotherRetryUpgradesReferenceOrderWithoutReplacingPreciseMockups() {
        drapeFixture(CommerceDrapeReuseWorkflow.LEGACY_PROMPT, "[\"scene\",\"guide\",\"art\"]");
        var result = service.retry("batch", "old", user);
        assertEquals("QUEUED", result.execution().status());
        assertEquals("MOCKUP", result.execution().stage());
        verify(limits).reserve("run", "org", KEY, "MOCKUP");
        verify(limits, never()).initialize(anyString(), anyString(), anyInt());
        verify(db).update(startsWith("INSERT INTO commerce_batch_artwork_jobs"), eq(result.artifactId()), eq(KEY),
                eq(CommerceDrapeReuseWorkflow.PROMPT_DETAIL_V4), eq("[\"guide\",\"scene\",\"art\"]"), anyString(), eq("old"), eq("run"), eq("org"));
        verify(db).update(contains("kind='DRAPE_MASTER' AND status='READY'"), eq("old"), eq("run"), eq("org"));
        verify(db, times(4)).update(anyString(), any(Object[].class));
        verify(db, never()).update(contains("storage_key="), any(Object[].class));
    }

    @Test void currentMotherKeepsReferenceOrderAndCanResumeEachStoppedStatus() {
        for (String status : List.of("WAITING_INPUT", "PAUSED", "FAILED")) {
            drapeFixture(CommerceDrapeReuseWorkflow.PROMPT, "[\"guide\",\"scene\",\"art\"]");
            drapeRun("MOCKUP", status, null, null);
            var result = service.retry("batch", "old", user);
            verify(db).update(startsWith("INSERT INTO commerce_batch_artwork_jobs"), eq(result.artifactId()), eq(KEY),
                    eq(CommerceDrapeReuseWorkflow.PROMPT_DETAIL_V4), eq("[\"guide\",\"scene\",\"art\"]"), anyString(), eq("old"), eq("run"), eq("org"));
        }
    }

    @Test void motherCannotBeReplacedDuringWorkOrWithAnUnclearedLease() {
        drapeFixture(CommerceDrapeReuseWorkflow.PROMPT, "[\"guide\",\"scene\",\"art\"]");
        for (String state : List.of("RUNNING", "QUEUED", "UNKNOWN", "COMPLETED")) {
            drapeRun("MOCKUP", state, null, null);
            assertThrows(BusinessException.class, () -> service.retry("batch", "old", user));
        }
        drapeRun("REVIEW", "PAUSED", null, null);
        assertThrows(BusinessException.class, () -> service.retry("batch", "old", user));
        drapeRun("MOCKUP", "WAITING_INPUT", "stale", null);
        assertThrows(BusinessException.class, () -> service.retry("batch", "old", user));
        drapeRun("MOCKUP", "WAITING_INPUT", null, Instant.now().minusSeconds(1));
        assertThrows(BusinessException.class, () -> service.retry("batch", "old", user));
        verifyNoInteractions(limits);
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void motherReplacementStopsBeforeReservationForPublicationsOrLocalDerivatives() {
        drapeFixture(CommerceDrapeReuseWorkflow.PROMPT, "[\"guide\",\"scene\",\"art\"]");
        when(db.queryForList(PUBLICATION_LOCK, "run", "org")).thenReturn(List.of(Map.of("item_id", "frozen-publication")));
        assertThrows(BusinessException.class, () -> service.retry("batch", "old", user));
        when(db.queryForList(PUBLICATION_LOCK, "run", "org")).thenReturn(List.of());
        for (String kind : List.of("DRAPE_BLANK", "DRAPED_FRONT")) {
            when(db.queryForList(DRAPE_JOBS_LOCK, "run", "org")).thenReturn(List.of(
                    Map.of("kind", kind, "capture_id", "capture", "status", "READY")));
            assertThrows(BusinessException.class, () -> service.retry("batch", "old", user));
        }
        verifyNoInteractions(limits);
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void unresolvedMotherBatchNeverGeneratesAnotherPaidAttempt() {
        drapeFixture(CommerceDrapeReuseWorkflow.PROMPT, "[\"guide\",\"scene\",\"art\"]");
        for (String state : List.of("PREPARED", "PENDING", "QUEUED", "RUNNING", "SUBMITTING", "UNKNOWN", "RESULT_RECEIVED")) {
            when(db.queryForList(DRAPE_JOBS_LOCK, "run", "org")).thenReturn(List.of(Map.of("status", state)));
            assertThrows(BusinessException.class, () -> service.retry("batch", "old", user), state);
        }
        verifyNoInteractions(limits);
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void missingOrUnrecognizedMotherReferenceBindingsFailBeforeReservation() {
        for (var candidate : List.of(
                List.of("unknown prompt", "[\"guide\",\"scene\",\"art\"]"),
                List.of(CommerceDrapeReuseWorkflow.PROMPT, "[\"wrong-guide\",\"scene\",\"art\"]"),
                List.of(CommerceDrapeReuseWorkflow.PROMPT, "[\"guide\",\"scene\",\"wrong-art\"]"),
                List.of(CommerceDrapeReuseWorkflow.PROMPT, "[\"guide\",\"scene\"]"),
                List.of(CommerceDrapeReuseWorkflow.PROMPT, "broken-json"))) {
            drapeFixture(candidate.getFirst(), candidate.get(1));
            assertThrows(BusinessException.class, () -> service.retry("batch", "old", user));
        }
        verifyNoInteractions(limits);
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void motherRetryConsumesOnlyOriginalBudgetAndReplayNeverRequeues() {
        drapeFixture(CommerceDrapeReuseWorkflow.PROMPT, "[\"guide\",\"scene\",\"art\"]");
        when(limits.reserve("run", "org", KEY, "MOCKUP")).thenReturn(false);
        assertThrows(BusinessException.class, () -> service.retry("batch", "old", user));
        verify(db, never()).update(anyString(), any(Object[].class));
        job("RETRIED");
        when(db.queryForList(REPLACEMENT_LOCK, "run", "org", KEY)).thenReturn(List.of(Map.of("id", "new-master")));
        drapeRun("MOCKUP", "RUNNING", "active", Instant.now().plusSeconds(60));
        assertEquals("new-master", service.retry("batch", "old", user).artifactId());
        verify(limits, times(1)).reserve("run", "org", KEY, "MOCKUP");
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    private void drapeFixture(String prompt, String refs) {
        drapeRun("MOCKUP", "WAITING_INPUT", null, null);
        var master = Map.<String,Object>of("id", "old", "request_key", "original-request", "status", "READY",
                "kind", "DRAPE_MASTER", "capture_id", "capture", "capture_version", 7L,
                "storage_key", "archived-mother", "prompt", prompt, "reference_keys", refs);
        when(db.queryForList(JOB_LOCK, "old", "run", "org")).thenReturn(List.of(master));
        var jobs = new java.util.ArrayList<Map<String,Object>>();
        jobs.add(master);
        jobs.add(Map.of("kind", "DRAPE_GUIDE", "capture_id", "capture", "capture_version", 7L,
                "storage_key", "guide", "status", "READY", "reference_keys", "[\"template\",\"uv\",\"art\"]"));
        for (int i=0; i<20; i++) jobs.add(Map.of("id", "mockup-"+i, "kind", "MOCKUP_FRONT", "status", "READY"));
        when(db.queryForList(DRAPE_JOBS_LOCK, "run", "org")).thenReturn(jobs);
    }

    private void drapeRun(String stage, String status, String token, Instant until) {
        var prior = run(status, "u");
        when(db.query(eq(RUN_LOCK), org.mockito.ArgumentMatchers.<RowMapper<CommerceBatchExecution>>any(),
                eq("run"), eq("org"), eq("batch"))).thenReturn(List.of(new CommerceBatchExecution(
                prior.id(), prior.organizationId(), prior.batchId(), prior.createdBy(), stage, status,
                prior.message(), token, until, prior.createdAt(), prior.updatedAt())));
    }

    private void lockedRun(String status, String owner) {
        when(db.query(eq(RUN_LOCK), org.mockito.ArgumentMatchers.<RowMapper<CommerceBatchExecution>>any(),
                eq("run"), eq("org"), eq("batch"))).thenReturn(List.of(run(status, owner)));
    }

    private void job(String status) {
        when(db.queryForList(JOB_LOCK, "old", "run", "org"))
                .thenReturn(List.of(Map.of("id", "old", "request_key", "original-request", "status", status)));
    }

    static CommerceBatchExecution run(String status, String owner) {
        var now = Instant.parse("2026-09-14T00:00:00Z");
        return new CommerceBatchExecution("run", "org", "batch", owner, "MOCKUP", status, "original",
                "stale-lease", now.minusSeconds(1), now, now);
    }

    static PlatformPrincipal user(String organization, String owner, List<String> permissions) {
        return new PlatformPrincipal(owner, organization, "code", owner, "User", "", true, List.of(), permissions);
    }
}
