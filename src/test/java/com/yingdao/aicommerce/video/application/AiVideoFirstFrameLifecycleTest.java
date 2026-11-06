package com.yingdao.aicommerce.video.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection;
import com.yingdao.aicommerce.storage.OssStorageService;
import com.yingdao.aicommerce.video.application.provider.VideoGenerationProvider;
import com.yingdao.aicommerce.video.application.provider.VideoProviderException;
import com.yingdao.aicommerce.video.application.provider.VideoProviderRegistry;
import com.yingdao.aicommerce.video.domain.*;
import com.yingdao.aicommerce.video.mapper.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercise the paid-generation boundary using persisted snapshots rather than shared mutable job mocks. */
class AiVideoFirstFrameLifecycleTest {
    @TempDir Path storage;
    private final AiVideoTaskMapper tasks = mock(AiVideoTaskMapper.class);
    private final AiVideoTaskItemMapper items = mock(AiVideoTaskItemMapper.class);
    private final AiVideoTaskAssetMapper assets = mock(AiVideoTaskAssetMapper.class);
    private final AiVideoGenerationJobMapper jobs = mock(AiVideoGenerationJobMapper.class);
    private final ModelConfigService models = mock(ModelConfigService.class);
    private final VideoProviderRegistry registry = mock(VideoProviderRegistry.class);
    private final VideoGenerationProvider provider = mock(VideoGenerationProvider.class);
    private final VideoFirstFrameAuditService audit = mock(VideoFirstFrameAuditService.class);
    private final Map<String, AiVideoGenerationJob> storedJobs = new LinkedHashMap<>();
    private final List<AiVideoTaskAsset> storedAssets = new ArrayList<>();
    private final RuntimeModelConnection route = new RuntimeModelConnection("route-original", "https://unused.invalid",
            "minimax-h3", "OPENAI_COMPATIBLE", "DAYAN", "test-key");
    private static final String TIMELINE = "【结构契约】保持两只袖子\n【时间轴】0-3秒正面；3-8秒转身；8-12秒走近";
    private AiVideoTask task;
    private AiVideoTaskItem item;
    private AiVideoGenerationService service;
    private VideoReviewFinalizer finalizer;
    private int otherActiveJobs;

    @BeforeAll static void metadata() {
        for (Class<?> type : List.of(AiVideoTask.class, AiVideoTaskItem.class, AiVideoTaskAsset.class, AiVideoGenerationJob.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "lifecycle"), type);
        }
    }

    @BeforeEach void setup() {
        task = new AiVideoTask("org", "task", null, 1, "script", "{}", "US", "PRODUCT_ONLY", "AI",
                "clothing", "studio", "none", false, "9:16", "720p", false, false, "{}", "{}", "user");
        task.startVideos(route.id(), route.modelId());
        item = new AiVideoTaskItem("org", task.getId(), "item", "sku", "source.png", storage.resolve("source.png").toString(), "image/png", 1);
        item.updateVideoPrompt(TIMELINE);
        item.videoRunning();
        when(tasks.selectById(task.getId())).thenReturn(task);
        when(items.selectById(item.getId())).thenReturn(item);
        when(items.updateById(any(AiVideoTaskItem.class))).thenReturn(1);
        when(items.selectList(any())).thenAnswer(call -> matches(call.getArgument(0), item) ? List.of(item) : List.of());
        when(assets.selectList(any())).thenAnswer(call -> storedAssets.stream().filter(value -> matches(call.getArgument(0), value)).toList());
        when(assets.selectCount(any())).thenAnswer(call -> storedAssets.stream().filter(value -> matches(call.getArgument(0), value)).count());
        when(assets.insert(any(AiVideoTaskAsset.class))).thenAnswer(call -> { storedAssets.add(call.getArgument(0)); return 1; });
        when(assets.update(isNull(), any())).thenAnswer(call -> {
            LambdaUpdateWrapper<?> update = call.getArgument(1);
            List<AiVideoTaskAsset> selected = storedAssets.stream().filter(value -> matches(update, value)).toList();
            selected.forEach(value -> applySet(update, value));
            return selected.size();
        });
        when(jobs.selectById(anyString())).thenAnswer(call -> copy(storedJobs.get(call.getArgument(0))));
        when(jobs.selectList(any())).thenAnswer(call -> storedJobs.values().stream().filter(value -> matches(call.getArgument(0), value)).map(this::copy).toList());
        when(jobs.selectOne(any())).thenAnswer(call -> storedJobs.values().stream().filter(value -> matches(call.getArgument(0), value))
                .max(Comparator.comparing(AiVideoGenerationJob::getCreatedAt)).map(this::copy).orElse(null));
        when(jobs.selectCount(any())).thenAnswer(call -> {
            AbstractWrapper<?, ?, ?> query = call.getArgument(0);
            long count = storedJobs.values().stream().filter(value -> matches(query, value)).count();
            return count + (query.getSqlSegment().contains("organization_id") && query.getSqlSegment().contains("status IN") ? otherActiveJobs : 0);
        });
        when(jobs.insert(any(AiVideoGenerationJob.class))).thenAnswer(call -> save(call.getArgument(0)));
        when(jobs.updateById(any(AiVideoGenerationJob.class))).thenAnswer(call -> save(call.getArgument(0)));
        when(jobs.update(any(AiVideoGenerationJob.class), any())).thenAnswer(call -> {
            AiVideoGenerationJob updated = call.getArgument(0);
            AiVideoGenerationJob stored = storedJobs.get(updated.getId());
            return stored != null && matches(call.getArgument(1), stored) ? save(updated) : 0;
        });
        when(models.videoConnections("org")).thenReturn(List.of(route));
        when(models.connectionById("org", route.id())).thenReturn(Optional.of(route));
        when(registry.require("DAYAN")).thenReturn(provider);
        when(provider.effectiveResolution(anyString())).thenAnswer(call -> call.getArgument(0));
        when(provider.submit(any(), any())).thenReturn(new VideoGenerationProvider.SubmitResult("accepted-id", "QUEUED", "{}", "{}"));
        finalizer = new VideoReviewFinalizer(jobs, assets, items);
        service = newService();
        storedAssets.add(asset("STORYBOARD_FIRST_FRAME", "first.png", null));
        storedAssets.add(asset("STORYBOARD_GRID", "grid.png", null));
    }

    @Test void reviewingRestartApprovesSavedCandidateWithoutProviderOrOriginalModelLookup() {
        AiVideoGenerationJob job = reviewing("run", 1);
        AiVideoTaskAsset candidate = candidate(job);
        answer("SINGLE_SCENE", 0.95, "完整连续场景");
        clearInvocations(models, registry, provider);
        poll(job);
        assertThat(stored(job).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(candidate.getAssetType()).isEqualTo("VIDEO_RESULT");
        assertThat(item.getStatus()).isEqualTo("VIDEO_READY");
        verifyNoInteractions(models, registry, provider);
        verify(audit).audit(task, item, candidate.getFilePath(), job.getId());
    }

    @Test void candidateAloneDoesNotCompleteTheItemOrTask() {
        candidate(reviewing("run", 1));
        ReflectionTestUtils.invokeMethod(service, "refreshTask", task.getId());
        assertThat(item.getStatus()).isEqualTo("VIDEO_GENERATING");
        assertThat(task.getStatus()).isEqualTo("VIDEO_GENERATING");
    }

    @Test void unknownIncludingImageModel502NeverTriggersAutomaticPaidVideoOrImageRetry() {
        AiVideoGenerationJob job = reviewing("run", 1);
        AiVideoTaskAsset candidate = candidate(job);
        when(audit.audit(any(),any(),anyString(),anyString())).thenReturn(new VideoFirstFrameAuditService.AuditResult("UNKNOWN",0,"图片模型（502）temporarily unavailable",null,0,false));
        poll(job);
        assertThat(stored(job).getErrorMessage()).startsWith(AiVideoGenerationService.REVIEW_PAUSED);
        assertThat(AiVideoImageProcessor.retryablePipelineImageFailure(item.getStatus(), item.getErrorMessage())).isFalse();
        assertThat(AiVideoImageProcessor.retryableImageTransientFailure(item.getErrorMessage())).isFalse();
        service.retryTransientSubmissionFailures();
        service.retryGridRejectedVideos();
        service.recoverUnsubmittedItems();
        assertThat(storedJobs).hasSize(1);
        assertThat(candidate.getAssetType()).isEqualTo("VIDEO_REVIEW_CANDIDATE");
        assertThat(item.getStatus()).isEqualTo("VIDEO_FAILED");
        verify(provider, never()).submit(any(), any());
        verify(provider, never()).query(any(), anyString());
    }

    @Test void evaluatedLowConfidenceSingleSceneKeepsExistingPermissivePolicyWithoutRegeneration() {
        AiVideoGenerationJob job = reviewing("run", 1);
        AiVideoTaskAsset candidate = candidate(job);
        answer("SINGLE_SCENE", 0.79, "证据不足");
        poll(job);
        service.retryGridRejectedVideos();
        assertThat(stored(job).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(stored(job).getErrorMessage()).isNull();
        assertThat(candidate.getAssetType()).isEqualTo("VIDEO_RESULT");
        verify(provider, never()).submit(any(), any());
    }

    @Test void confirmedGridRetainsPaidReceiptAndCandidateWhileWaitingForRetry() {
        AiVideoGenerationJob job = reviewing("run", 1);
        AiVideoTaskAsset candidate = candidate(job);
        answer("GRID", 0.99, "九个独立镜头");
        poll(job);
        assertThat(stored(job).getStatus()).isEqualTo("FAILED");
        assertThat(stored(job).getCostAmount()).isEqualByComparingTo("1.25");
        assertThat(stored(job).getUsageJson()).contains("providerReceipt", "GRID", "confirmedRejection");
        assertThat(candidate.getAssetType()).isEqualTo("VIDEO_REVIEW_CANDIDATE");
        assertThat(storedAssets).noneMatch(value -> "VIDEO_RESULT".equals(value.getAssetType()));
        assertThat(task.getStatus()).isEqualTo("VIDEO_GENERATING");
    }

    @Test void onlyTwoRegenerationsUseTheSameRunAndRouteAndPreserveOriginalTimeline() {
        AiVideoGenerationJob initial = reviewing("original-run", 1);
        candidate(initial);
        answer("GRID", 0.99, "九个独立镜头");
        for (int rejection = 0; rejection < 3; rejection++) {
            AiVideoGenerationJob current = latest();
            if (rejection > 0) {
                current.awaitingReview("provider-output", new BigDecimal("1.25"), "{\"providerReceipt\":true}");
                save(current);
                candidate(current);
            }
            poll(current);
            service.retryGridRejectedVideos();
        }
        service.retryGridRejectedVideos();
        service.retryTransientSubmissionFailures();
        assertThat(storedJobs).hasSize(3);
        assertThat(storedJobs.values()).allMatch(value -> "original-run".equals(value.getRunId()) && route.id().equals(value.getModelConfigId()));
        assertThat(storedJobs.values()).extracting(AiVideoGenerationJob::getAttemptNo).containsExactly(1, 2, 3);
        assertThat(latest().getErrorMessage()).contains("自动重做已达上限");
        assertThat(task.getStatus()).isEqualTo("FAILED");
        var requests = org.mockito.ArgumentCaptor.forClass(VideoGenerationProvider.GenerationRequest.class);
        verify(provider, times(2)).submit(eq(route), requests.capture());
        assertThat(requests.getAllValues()).allMatch(request -> request.prompt().contains(TIMELINE) && request.prompt().contains("首帧画面纠正"));
    }

    @Test void capacityExhaustionAndRestartKeepQualityRetryRunAndBudget() {
        AiVideoGenerationJob initial = reviewing("original-run", 1);
        candidate(initial);
        answer("GRID", 0.99, "独立画面");
        poll(initial);
        otherActiveJobs = 500;
        service.retryGridRejectedVideos();
        item.startVideo(); // reserveSubmissionSlot can lose the capacity race after the outer check.
        service.recoverUnsubmittedItems();
        verify(provider, never()).submit(any(), any());
        assertThat(storedJobs).hasSize(1);
        otherActiveJobs = 0;
        newService().recoverUnsubmittedItems();
        assertThat(storedJobs).hasSize(2);
        assertThat(latest().getRunId()).isEqualTo("original-run");
        assertThat(latest().getModelConfigId()).isEqualTo(route.id());
        assertThat(latest().getAttemptNo()).isEqualTo(2);
        verify(provider).submit(eq(route), any());
    }

    @Test void candidatePersistedBeforeReviewStateIsReusedWithoutDownloadingAgain() {
        AiVideoGenerationJob job = reviewing("run", 1);
        AiVideoTaskAsset candidate = candidate(job);
        job.running("RUNNING", Instant.now());
        save(job);
        when(provider.query(route, "provider-id")).thenReturn(new VideoGenerationProvider.QueryResult("SUCCEEDED",
                "file:/must-not-be-downloaded", new BigDecimal("1.25"), Map.of("providerReceipt", true), null, "{}", "{}"));
        answer("SINGLE_SCENE", 0.95, "单场景");
        poll(job);
        assertThat(stored(job).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(storedAssets.stream().filter(value -> value.getId().equals(candidate.getId()))).hasSize(1);
        assertThat(candidate.getAssetType()).isEqualTo("VIDEO_RESULT");
        verify(assets, never()).insert(any(AiVideoTaskAsset.class));
        verify(audit).audit(task, item, candidate.getFilePath(), job.getId());
    }

    @Test void finalizerCompareAndSetPublishesOnceAndArchivesPreviousResultWithoutDeletingBytes() {
        AiVideoGenerationJob job = reviewing("run", 1);
        AiVideoTaskAsset candidate = candidate(job);
        AiVideoTaskAsset previous = asset("VIDEO_RESULT", "old-paid-video.mp4", "old-job");
        storedAssets.add(previous);
        job.succeeded(job.getOutputUrl(), job.getCostAmount(), job.getUsageJson());
        assertThat(finalizer.finish(job, item, candidate, true)).isTrue();
        assertThat(finalizer.finish(job, item, candidate, true)).isFalse();
        assertThat(candidate.getAssetType()).isEqualTo("VIDEO_RESULT");
        assertThat(previous.getAssetType()).isEqualTo("VIDEO_PREVIOUS_RESULT");
        assertThat(previous.getShotSequence()).isNull();
        assertThat(previous.getFilePath()).isEqualTo("old-paid-video.mp4");
        verify(items, times(1)).updateById(item);
        verify(assets, never()).deleteById(anyString());
    }

    @Test void qualityRetryProviderRejectionPausesWithoutFailingOverToAnotherRoute() {
        AiVideoGenerationJob job = reviewing("original-run", 1);
        candidate(job);
        answer("GRID", 0.99, "拼图");
        poll(job);
        RuntimeModelConnection alternative = new RuntimeModelConnection("route-other", "https://unused.invalid", "minimax-h3", "OPENAI_COMPATIBLE", "DAYAN", "test-key");
        when(models.videoConnections("org")).thenReturn(List.of(route, alternative));
        when(provider.submit(any(), any())).thenThrow(new VideoProviderException("厂商（502）", true));
        service.retryGridRejectedVideos();
        service.retryTransientSubmissionFailures();
        assertThat(storedJobs).hasSize(2);
        assertThat(latest().getModelConfigId()).isEqualTo(route.id());
        assertThat(latest().getErrorMessage()).startsWith(AiVideoGenerationService.REVIEW_PAUSED);
        verify(provider, times(1)).submit(eq(route), any());
        verify(provider, never()).submit(eq(alternative), any());
    }

    @Test void oldResultCannotMaskCurrentGridRejectionOrBlockItsAutomaticRetry() {
        AiVideoTaskAsset oldResult = asset("VIDEO_RESULT", "old-paid-video.mp4", "previous-job");
        storedAssets.add(oldResult);
        AiVideoGenerationJob current = reviewing("current-run", 1);
        AiVideoTaskAsset candidate = candidate(current);
        ReflectionTestUtils.invokeMethod(service, "refreshTask", task.getId());
        assertThat(item.getStatus()).isEqualTo("VIDEO_GENERATING");
        answer("GRID", 0.99, "多个独立画面");
        poll(current);
        assertThat(item.getStatus()).isEqualTo("VIDEO_FAILED");
        assertThat(task.getStatus()).isEqualTo("VIDEO_GENERATING");
        assertThat(candidate.getAssetType()).isEqualTo("VIDEO_REVIEW_CANDIDATE");
        service.retryGridRejectedVideos();
        assertThat(storedJobs).hasSize(2);
        assertThat(latest().getRunId()).isEqualTo("current-run");
        assertThat(latest().getModelConfigId()).isEqualTo(route.id());
        assertThat(oldResult.getFilePath()).isEqualTo("old-paid-video.mp4");
        verify(provider).submit(eq(route), any());
    }

    @Test void insufficientCapacityLeavesInterruptedNotSentReservationRecoverable() {
        AiVideoGenerationJob initial = reviewing("original-run", 1);
        candidate(initial);
        answer("GRID", 0.99, "拼图");
        poll(initial);
        AiVideoGenerationJob interrupted = interruptedQualityRetry();
        otherActiveJobs = 500;
        newService().recoverUnsubmittedItems();
        assertThat(stored(interrupted).getStatus()).isEqualTo("SUBMITTING");
        assertThat(stored(interrupted).getRequestState()).isEqualTo("NOT_SENT");
        assertThat(storedJobs).hasSize(2);
        verify(provider, never()).submit(any(), any());
        otherActiveJobs = 0;
        newService().recoverUnsubmittedItems();
        assertThat(storedJobs).hasSize(3);
        assertThat(latest().getRunId()).isEqualTo("original-run");
        assertThat(latest().getModelConfigId()).isEqualTo(route.id());
        verify(provider).submit(eq(route), any());
    }

    @Test void capacityRaceAfterTerminatingNotSentReservationAndAnotherRestartPreservesRunAndRetryBudget() {
        AiVideoGenerationJob initial = reviewing("original-run", 1);
        candidate(initial);
        answer("GRID", 0.99, "拼图");
        poll(initial);
        AiVideoGenerationJob interrupted = interruptedQualityRetry();
        Queue<Runnable> pending = new ArrayDeque<>();
        newService(pending::add).recoverUnsubmittedItems();
        assertThat(pending).hasSize(1);
        assertThat(stored(interrupted).getStatus()).isEqualTo("FAILED");
        otherActiveJobs = 500;
        pending.remove().run();
        assertThat(item.getStatus()).isEqualTo("VIDEO_SUBMITTING");
        newService().recoverUnsubmittedItems();
        verify(provider, never()).submit(any(), any());
        otherActiveJobs = 0;
        newService().recoverUnsubmittedItems();
        assertThat(latest().getRunId()).isEqualTo("original-run");
        assertThat(latest().getModelConfigId()).isEqualTo(route.id());
        assertThat(latest().getAttemptNo()).isEqualTo(3);
        for (int rejection = 0; rejection < 2; rejection++) {
            AiVideoGenerationJob current = latest();
            current.awaitingReview("provider-output", new BigDecimal("1.25"), "{\"providerReceipt\":true}");
            save(current);
            candidate(current);
            poll(current);
            service.retryGridRejectedVideos();
        }
        assertThat(storedJobs).hasSize(4); // Initial generation, one unsent reservation, two paid redraws.
        assertThat(storedJobs.values()).allMatch(value -> "original-run".equals(value.getRunId()) && route.id().equals(value.getModelConfigId()));
        assertThat(latest().getErrorMessage()).contains("自动重做已达上限");
        verify(provider, times(2)).submit(eq(route), any());
    }

    private AiVideoGenerationJob interruptedQualityRetry() {
        AiVideoGenerationJob job = new AiVideoGenerationJob("original-run", "org", task.getId(), item.getId(), 2,
                route.id(), route.provider(), route.modelId(), 12, "9:16", "720p");
        ReflectionTestUtils.setField(job, "updatedAt", Instant.now().minusSeconds(60));
        save(job);
        item.startVideo();
        return job;
    }

    private AiVideoGenerationService newService() { return newService(Runnable::run); }
    @SuppressWarnings("unchecked") private AiVideoGenerationService newService(java.util.concurrent.Executor executor) {
        ObjectProvider<OssStorageService> oss = mock(ObjectProvider.class);
        VideoReferenceLinkService links = mock(VideoReferenceLinkService.class);
        when(links.isPubliclyReachable()).thenReturn(true);
        when(links.deliveryAsset(anyString())).thenReturn("https://reference.invalid/frame.jpg");
        when(links.source(anyString())).thenReturn("https://reference.invalid/source.jpg");
        AiVideoGenerationService value = new AiVideoGenerationService(tasks, items, assets, jobs, mock(VideoScriptMapper.class),
                models, registry, links, mock(VideoReferenceDeliveryService.class), oss, new ObjectMapper(), executor, 500);
        value.setFirstFrameAudit(audit);
        value.setReviewFinalizer(finalizer);
        return value;
    }

    private AiVideoGenerationJob reviewing(String run, int attempt) {
        AiVideoGenerationJob job = new AiVideoGenerationJob(run, "org", task.getId(), item.getId(), attempt,
                route.id(), route.provider(), route.modelId(), 12, "9:16", "720p");
        job.accepted("provider-id", "RUNNING", Instant.now());
        job.awaitingReview("provider-output", new BigDecimal("1.25"), "{\"providerReceipt\":true}");
        save(job);
        return job;
    }
    private AiVideoTaskAsset candidate(AiVideoGenerationJob job) {
        AiVideoTaskAsset value = asset("VIDEO_REVIEW_CANDIDATE", storage.resolve("video-" + job.getId() + ".mp4").toString(), job.getId());
        storedAssets.add(value);
        return value;
    }
    private AiVideoTaskAsset asset(String type, String path, String jobId) {
        return new AiVideoTaskAsset("org", task.getId(), item.getId(), type, type.equals("VIDEO_REVIEW_CANDIDATE") ? null : 1,
                path, "video/mp4", 42, jobId == null ? null : "{\"jobId\":\"" + jobId + "\"}", route.modelId());
    }
    private void answer(String layout, double confidence, String reason) {
        when(audit.audit(any(), any(), anyString(), anyString())).thenReturn(new VideoFirstFrameAuditService.AuditResult(layout, confidence, reason, null, 0));
    }
    private void poll(AiVideoGenerationJob job) { ReflectionTestUtils.invokeMethod(service, "poll", job.getId()); }
    private AiVideoGenerationJob stored(AiVideoGenerationJob job) { return storedJobs.get(job.getId()); }
    private AiVideoGenerationJob latest() { return storedJobs.values().stream().max(Comparator.comparing(AiVideoGenerationJob::getCreatedAt)).orElseThrow(); }
    private int save(AiVideoGenerationJob job) { storedJobs.put(job.getId(), copy(job)); return 1; }
    private AiVideoGenerationJob copy(AiVideoGenerationJob job) {
        if (job == null) return null;
        AiVideoGenerationJob result = new AiVideoGenerationJob(job.getRunId(), job.getOrganizationId(), job.getTaskId(), job.getItemId(),
                job.getAttemptNo(), job.getModelConfigId(), job.getProvider(), job.getModelId(), job.getDurationSeconds(), job.getAspectRatio(), job.getResolution());
        for (var field : AiVideoGenerationJob.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) ReflectionTestUtils.setField(result, field.getName(), ReflectionTestUtils.getField(job, field.getName()));
        }
        return result;
    }

    // The small mapper double evaluates bound predicates so company/run/status filters remain part of each test.
    private static boolean matches(AbstractWrapper<?, ?, ?> query, Object row) {
        String sql = query.getSqlSegment();
        Map<String, Object> parameters = query.getParamNameValuePairs();
        var conditions = Pattern.compile("(\\w+) (=|LIKE|<=|<) #\\{ew.paramNameValuePairs\\.(MPGENVAL\\d+)}").matcher(sql);
        while (conditions.find()) {
            Object actual = ReflectionTestUtils.getField(row, property(conditions.group(1)));
            Object expected = parameters.get(conditions.group(3));
            boolean match = switch (conditions.group(2)) {
                case "=" -> Objects.equals(actual, expected);
                case "LIKE" -> actual != null && actual.toString().startsWith(expected.toString().replaceFirst("%$", ""));
                case "<" -> actual instanceof Instant value && value.isBefore((Instant) expected);
                case "<=" -> actual instanceof Instant value && !value.isAfter((Instant) expected);
                default -> throw new AssertionError("Unsupported condition");
            };
            if (!match) return false;
        }
        var lists = Pattern.compile("(\\w+) IN \\(([^)]+)\\)").matcher(sql);
        while (lists.find()) {
            Object actual = ReflectionTestUtils.getField(row, property(lists.group(1)));
            var names = Pattern.compile("MPGENVAL\\d+").matcher(lists.group(2));
            boolean found = false;
            while (names.find()) found |= Objects.equals(actual, parameters.get(names.group()));
            if (!found) return false;
        }
        var nulls = Pattern.compile("(\\w+) IS NULL").matcher(sql);
        while (nulls.find()) if (ReflectionTestUtils.getField(row, property(nulls.group(1))) != null) return false;
        return true;
    }
    private static void applySet(LambdaUpdateWrapper<?> update, Object row) {
        var changes = Pattern.compile("(\\w+)=#\\{ew.paramNameValuePairs\\.(MPGENVAL\\d+)}").matcher(update.getSqlSet());
        while (changes.find()) ReflectionTestUtils.setField(row, property(changes.group(1)), update.getParamNameValuePairs().get(changes.group(2)));
    }
    private static String property(String column) {
        StringBuilder value = new StringBuilder();
        boolean upper = false;
        for (char letter : column.toCharArray()) {
            if (letter == '_') upper = true;
            else { value.append(upper ? Character.toUpperCase(letter) : letter); upper = false; }
        }
        return value.toString();
    }
}
