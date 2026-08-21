package com.yingdao.aicommerce.video.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection;
import com.yingdao.aicommerce.storage.OssStorageService;
import com.yingdao.aicommerce.video.application.provider.VideoGenerationProvider;
import com.yingdao.aicommerce.video.application.provider.VideoGenerationProvider.GenerationRequest;
import com.yingdao.aicommerce.video.application.provider.VideoGenerationProvider.QueryResult;
import com.yingdao.aicommerce.video.application.provider.VideoGenerationProvider.Reference;
import com.yingdao.aicommerce.video.application.provider.VideoGenerationProvider.SubmitResult;
import com.yingdao.aicommerce.video.application.provider.VideoProviderException;
import com.yingdao.aicommerce.video.application.provider.VideoProviderRegistry;
import com.yingdao.aicommerce.video.domain.AiVideoGenerationJob;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import com.yingdao.aicommerce.video.domain.AiVideoTaskAsset;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import com.yingdao.aicommerce.video.domain.VideoScript;
import com.yingdao.aicommerce.video.mapper.AiVideoGenerationJobMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskAssetMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskMapper;
import com.yingdao.aicommerce.video.mapper.VideoScriptMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.UUID;

@Service
public class AiVideoGenerationService {
    private static final long MAX_VIDEO_BYTES = 500L * 1024 * 1024;
    private static final long DAYAN_SUBMISSION_INTERVAL_MILLIS = 500L;
    private static final int MAX_AUTOMATIC_TRANSIENT_RETRIES = 3;
    private static final long[] AUTOMATIC_RETRY_DELAYS_SECONDS = {30L, 90L, 300L};
    private static final List<String> ACTIVE_VIDEO_JOB_STATUSES = List.of("SUBMITTING", "QUEUED", "RUNNING");
    private final AiVideoTaskMapper taskMapper;
    private final AiVideoTaskItemMapper itemMapper;
    private final AiVideoTaskAssetMapper assetMapper;
    private final AiVideoGenerationJobMapper jobMapper;
    private final VideoScriptMapper scriptMapper;
    private final ModelConfigService modelConfigs;
    private final VideoProviderRegistry providers;
    private final VideoReferenceLinkService referenceLinks;
    private final VideoReferenceDeliveryService referenceDelivery;
    private final OssStorageService ossStorage;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final int maxConcurrentVideoSubmissions;
    private final Instant serviceStartedAt = Instant.now();
    private final Set<String> queuedSubmissions = ConcurrentHashMap.newKeySet();
    private final Object dayanSubmissionPacingMonitor = new Object();
    private long nextDayanSubmissionAtMillis;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public AiVideoGenerationService(AiVideoTaskMapper taskMapper, AiVideoTaskItemMapper itemMapper,
                                    AiVideoTaskAssetMapper assetMapper, AiVideoGenerationJobMapper jobMapper,
                                    VideoScriptMapper scriptMapper,
                                    ModelConfigService modelConfigs, VideoProviderRegistry providers,
                                    VideoReferenceLinkService referenceLinks, VideoReferenceDeliveryService referenceDelivery,
                                    ObjectProvider<OssStorageService> ossStorage,
                                    ObjectMapper objectMapper,
                                    @Qualifier("aiVideoGenerationExecutor") Executor executor,
                                    @Value("${app.video.max-concurrent-submissions:500}") int maxConcurrentVideoSubmissions) {
        this.taskMapper = taskMapper;
        this.itemMapper = itemMapper;
        this.assetMapper = assetMapper;
        this.jobMapper = jobMapper;
        this.scriptMapper = scriptMapper;
        this.modelConfigs = modelConfigs;
        this.providers = providers;
        this.referenceLinks = referenceLinks;
        this.referenceDelivery = referenceDelivery;
        this.ossStorage = ossStorage.getIfAvailable();
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.maxConcurrentVideoSubmissions = Math.max(1, maxConcurrentVideoSubmissions);
    }

    @Transactional
    public AiVideoTask start(AiVideoTask task) {
        List<RuntimeModelConnection> routes = videoRoutes(task);
        if (routes.isEmpty()) throw new IllegalStateException(missingRouteMessage(task));
        RuntimeModelConnection primary = routes.getFirst();
        if ("AUTO".equals(primary.provider())) throw new IllegalStateException("无法识别视频厂商，请编辑视频模型并选择厂商");
        List<AiVideoTaskItem> allItems = itemMapper.selectList(new LambdaQueryWrapper<AiVideoTaskItem>()
                .eq(AiVideoTaskItem::getTaskId, task.getId()).orderByAsc(AiVideoTaskItem::getCreatedAt));
        if (allItems.isEmpty()) throw new IllegalStateException("请先上传并生成商品图片");
        List<AiVideoTaskItem> items = allItems.stream().filter(item -> !hasVideoResult(item.getId())).toList();
        if (items.isEmpty()) throw new IllegalStateException("当前任务的全部视频已经生成，无需重复提交");
        for (AiVideoTaskItem item : items) {
            if (task.isDirectReferenceVideo()) validateDirectReferences(item.getId());
            else if (!hasRequiredImages(item.getId())) throw new IllegalStateException(item.getName() + " 的首帧或九宫格尚未生成");
            if (item.getVideoPromptText() == null || item.getVideoPromptText().isBlank()) {
                throw new IllegalStateException(item.getName() + " 的视频提示词不能为空");
            }
        }
        task.startVideos(primary.id(), primary.modelId());
        taskMapper.updateById(task);
        String runId = UUID.randomUUID().toString();
        for (AiVideoTaskItem item : items) {
            item.startVideo();
            itemMapper.updateById(item);
            dispatchAfterCommit(runId, task.getId(), item.getId());
        }
        return task;
    }

    /**
     * Starts one item's video as soon as its first frame and storyboard are ready.
     * The task may still have other items generating images; those items join the
     * same video phase independently when their own required images are complete.
     */
    public synchronized boolean startReadyItemDuringImages(String taskId, String itemId) {
        AiVideoTask task = taskMapper.selectById(taskId);
        AiVideoTaskItem item = itemMapper.selectById(itemId);
        if (task == null || item == null
                || !List.of("IMAGE_GENERATING", "VIDEO_GENERATING").contains(task.getStatus())) return false;
        if (hasVideoResult(itemId)
                || List.of("VIDEO_SUBMITTING", "VIDEO_GENERATING", "VIDEO_READY").contains(item.getStatus())) return true;
        if (!hasRequiredImages(itemId)) return false;
        if (item.getVideoPromptText() == null || item.getVideoPromptText().isBlank()) {
            throw new IllegalStateException(item.getName() + " 的视频提示词不能为空");
        }
        List<RuntimeModelConnection> routes = videoRoutes(task);
        if (routes.isEmpty()) throw new IllegalStateException(missingRouteMessage(task));
        RuntimeModelConnection primary = routes.getFirst();
        if ("AUTO".equals(primary.provider())) {
            throw new IllegalStateException("无法识别视频厂商，请编辑视频模型并选择厂商");
        }
        long activeJobs = jobMapper.selectCount(new LambdaQueryWrapper<AiVideoGenerationJob>()
                .eq(AiVideoGenerationJob::getItemId, itemId)
                .in(AiVideoGenerationJob::getStatus, ACTIVE_VIDEO_JOB_STATUSES));
        if (activeJobs > 0) return true;
        if ("IMAGE_GENERATING".equals(task.getStatus())) {
            task.startVideos(primary.id(), primary.modelId());
            taskMapper.updateById(task);
        }
        item.startVideo();
        itemMapper.updateById(item);
        dispatchAfterCommit(UUID.randomUUID().toString(), taskId, itemId);
        return true;
    }

    public void refreshPipelinedTask(String taskId) {
        refreshTask(taskId);
    }

    private void dispatchAfterCommit(String runId, String taskId, String itemId) {
        Runnable dispatch = () -> queueSubmission(runId, taskId, itemId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { dispatch.run(); }
            });
        } else {
            dispatch.run();
        }
    }

    private void queueSubmission(String runId, String taskId, String itemId) {
        if (!queuedSubmissions.add(itemId)) return;
        executor.execute(() -> {
            try {
                submitNext(runId, taskId, itemId);
            } finally {
                queuedSubmissions.remove(itemId);
            }
        });
    }

    @Scheduled(initialDelay = 2_000, fixedDelay = 5_000)
    public void recoverUnsubmittedItems() {
        recoverInterruptedNotSentJobs();
        List<AiVideoTaskItem> stuck = itemMapper.selectList(new LambdaQueryWrapper<AiVideoTaskItem>()
                .eq(AiVideoTaskItem::getStatus, "VIDEO_SUBMITTING")
                .orderByAsc(AiVideoTaskItem::getUpdatedAt));
        for (AiVideoTaskItem item : stuck) {
            AiVideoTask task = taskMapper.selectById(item.getTaskId());
            if (task == null || !"VIDEO_GENERATING".equals(task.getStatus()) || hasVideoResult(item.getId())) continue;
            if (!hasSubmissionCapacity(task.getOrganizationId())) continue;
            long activeJobs = jobMapper.selectCount(new LambdaQueryWrapper<AiVideoGenerationJob>()
                    .eq(AiVideoGenerationJob::getItemId, item.getId())
                    .in(AiVideoGenerationJob::getStatus, ACTIVE_VIDEO_JOB_STATUSES));
            if (activeJobs == 0) {
                queueSubmission(UUID.randomUUID().toString(), task.getId(), item.getId());
            }
        }
    }

    private void recoverInterruptedNotSentJobs() {
        List<AiVideoGenerationJob> interrupted = jobMapper.selectList(new LambdaQueryWrapper<AiVideoGenerationJob>()
                .eq(AiVideoGenerationJob::getStatus, "SUBMITTING")
                .eq(AiVideoGenerationJob::getRequestState, "NOT_SENT")
                .isNull(AiVideoGenerationJob::getProviderTaskId)
                .lt(AiVideoGenerationJob::getUpdatedAt, serviceStartedAt)
                .orderByAsc(AiVideoGenerationJob::getUpdatedAt));
        for (AiVideoGenerationJob job : interrupted) {
            AiVideoTask task = taskMapper.selectById(job.getTaskId());
            AiVideoTaskItem item = itemMapper.selectById(job.getItemId());
            if (task == null || item == null || !"VIDEO_GENERATING".equals(task.getStatus())
                    || !"VIDEO_SUBMITTING".equals(item.getStatus()) || hasVideoResult(item.getId())) continue;
            job.failed("NOT_SENT", "提交服务重启中断，厂商未返回任务 ID；系统已自动重新提交");
            jobMapper.updateById(job);
            if (!hasSubmissionCapacity(task.getOrganizationId()) || !queuedSubmissions.add(item.getId())) continue;
            item.startVideo();
            itemMapper.updateById(item);
            executor.execute(() -> {
                try {
                    retrySameRoute(job.getRunId(), task.getId(), item.getId(), job.getModelConfigId());
                } finally {
                    queuedSubmissions.remove(item.getId());
                }
            });
        }
    }

    @Scheduled(initialDelay = 10_000, fixedDelay = 10_000)
    public void retryTransientSubmissionFailures() {
        List<AiVideoTaskItem> failedItems = itemMapper.selectList(new LambdaQueryWrapper<AiVideoTaskItem>()
                .eq(AiVideoTaskItem::getStatus, "VIDEO_FAILED")
                .orderByAsc(AiVideoTaskItem::getUpdatedAt));
        Instant now = Instant.now();
        for (AiVideoTaskItem item : failedItems) {
            AiVideoGenerationJob latest = latestJob(item.getId());
            if (latest == null || !"FAILED".equals(latest.getStatus())
                    || !isTransientSubmitFailure(latest.getErrorMessage())) continue;
            int failedAttempts = transientFailureCount(latest.getRunId(), item.getId(), latest.getModelConfigId());
            if (failedAttempts > MAX_AUTOMATIC_TRANSIENT_RETRIES) {
                String message = latest.getErrorMessage() + "；已自动重试 " + MAX_AUTOMATIC_TRANSIENT_RETRIES + " 次，可稍后手动重试";
                if (!message.equals(item.getErrorMessage())) {
                    item.updateVideoFailureMessage(message);
                    itemMapper.updateById(item);
                }
                continue;
            }
            long delaySeconds = AUTOMATIC_RETRY_DELAYS_SECONDS[Math.min(failedAttempts - 1,
                    AUTOMATIC_RETRY_DELAYS_SECONDS.length - 1)];
            if (latest.getUpdatedAt().plusSeconds(delaySeconds).isAfter(now)) continue;
            AiVideoTask task = taskMapper.selectById(item.getTaskId());
            if (task == null || !"VIDEO_GENERATING".equals(task.getStatus())) continue;
            if (!hasSubmissionCapacity(task.getOrganizationId())) continue;
            long activeJobs = jobMapper.selectCount(new LambdaQueryWrapper<AiVideoGenerationJob>()
                    .eq(AiVideoGenerationJob::getItemId, item.getId())
                    .in(AiVideoGenerationJob::getStatus, ACTIVE_VIDEO_JOB_STATUSES));
            if (activeJobs > 0 || !queuedSubmissions.add(item.getId())) continue;
            List<RuntimeModelConnection> routes = videoRoutes(task);
            RuntimeModelConnection connection = routes.stream()
                    .filter(route -> route.id().equals(latest.getModelConfigId())).findFirst().orElse(null);
            if (connection == null) {
                queuedSubmissions.remove(item.getId());
                continue;
            }
            item.startVideo();
            itemMapper.updateById(item);
            executor.execute(() -> {
                try {
                    retrySameRoute(latest.getRunId(), task.getId(), item.getId(), connection.id());
                } finally {
                    queuedSubmissions.remove(item.getId());
                }
            });
        }
    }

    private AiVideoGenerationJob latestJob(String itemId) {
        return jobMapper.selectOne(new LambdaQueryWrapper<AiVideoGenerationJob>()
                .eq(AiVideoGenerationJob::getItemId, itemId)
                .orderByDesc(AiVideoGenerationJob::getCreatedAt)
                .last("LIMIT 1"));
    }

    private int transientFailureCount(String runId, String itemId, String modelConfigId) {
        return Math.toIntExact(jobMapper.selectCount(new LambdaQueryWrapper<AiVideoGenerationJob>()
                .eq(AiVideoGenerationJob::getRunId, runId)
                .eq(AiVideoGenerationJob::getItemId, itemId)
                .eq(AiVideoGenerationJob::getModelConfigId, modelConfigId)
                .eq(AiVideoGenerationJob::getStatus, "FAILED")));
    }

    private boolean isTransientSubmitFailure(String message) {
        return retryableVideoConcurrencyFailure(message);
    }

    static boolean retryableVideoConcurrencyFailure(String message) {
        if (message == null) return false;
        String value = message.toLowerCase();
        return value.contains("（429）") || value.contains("(429)")
                || value.contains("（502）") || value.contains("(502)")
                || value.contains("（503）") || value.contains("(503)")
                || value.contains("（504）") || value.contains("(504)")
                || value.contains("生成服务暂不可用")
                || value.contains("temporarily unavailable")
                || value.contains("并发限制") || value.contains("并发已满")
                || value.contains("限流") || value.contains("concurrency limit exceeded")
                || value.contains("too many concurrent") || value.contains("rate limit")
                || value.contains("too many requests");
    }

    private boolean hasAutomaticRetryPending(AiVideoTaskItem item) {
        AiVideoGenerationJob latest = latestJob(item.getId());
        if (latest == null || !"FAILED".equals(latest.getStatus())
                || !isTransientSubmitFailure(latest.getErrorMessage())) return false;
        return transientFailureCount(latest.getRunId(), item.getId(), latest.getModelConfigId())
                <= MAX_AUTOMATIC_TRANSIENT_RETRIES;
    }

    private boolean hasVideoResult(String itemId) {
        return assetMapper.selectCount(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getItemId, itemId)
                .eq(AiVideoTaskAsset::getAssetType, "VIDEO_RESULT")) > 0;
    }

    private boolean hasRequiredImages(String itemId) {
        Set<String> types = new HashSet<>(assetMapper.selectList(new LambdaQueryWrapper<AiVideoTaskAsset>()
                        .eq(AiVideoTaskAsset::getItemId, itemId))
                .stream().map(AiVideoTaskAsset::getAssetType).toList());
        return types.contains("STORYBOARD_FIRST_FRAME") && types.contains("STORYBOARD_GRID");
    }

    private List<RuntimeModelConnection> videoRoutes(AiVideoTask task) {
        String selectedRoute = selectedRoute(task);
        List<RuntimeModelConnection> routes = modelConfigs.videoConnections(task.getOrganizationId()).stream()
                .filter(route -> !"FAST".equals(route.provider()))
                .toList();
        if ("QUALITY".equals(selectedRoute)) {
            return routes.stream()
                    .filter(route -> "DAYAN".equals(route.provider()) && !isDayanFast(route))
                    .toList();
        }
        if ("SPEED".equals(selectedRoute)) {
            return routes.stream()
                    .filter(route -> isDayanFast(route) || "H3_MULTI".equals(route.provider()))
                    .sorted(Comparator.comparingInt(route -> isDayanFast(route) ? 0 : 1))
                    .toList();
        }
        return routes;
    }

    private String selectedRoute(AiVideoTask task) {
        try {
            Map<?, ?> rules = objectMapper.readValue(task.getGenerationRulesJson(), Map.class);
            Object raw = rules.get("videoRoute");
            String route = raw == null ? "" : String.valueOf(raw).trim().toUpperCase();
            return List.of("QUALITY", "SPEED").contains(route) ? route : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isDayanFast(RuntimeModelConnection route) {
        return "DAYAN".equals(route.provider())
                && "minimax-h3-fast".equalsIgnoreCase(route.modelId().trim());
    }

    private String missingRouteMessage(AiVideoTask task) {
        return "SPEED".equals(selectedRoute(task))
                ? "低质量高速度线路尚未配置或启用"
                : "高质量低速度线路尚未配置或启用";
    }

    private void submitNext(String runId, String taskId, String itemId) {
        AiVideoTask task = taskMapper.selectById(taskId);
        AiVideoTaskItem item = itemMapper.selectById(itemId);
        if (task == null || item == null || !"VIDEO_GENERATING".equals(task.getStatus())) return;
        List<RuntimeModelConnection> routes = videoRoutes(task);
        Set<String> attempted = new HashSet<>(jobMapper.selectList(new LambdaQueryWrapper<AiVideoGenerationJob>()
                .eq(AiVideoGenerationJob::getItemId, itemId)
                .eq(AiVideoGenerationJob::getRunId, runId)).stream().map(AiVideoGenerationJob::getModelConfigId).toList());
        RuntimeModelConnection connection = routes.stream().filter(route -> !attempted.contains(route.id())).findFirst().orElse(null);
        if (connection == null) {
            item.videoFail(latestFailureMessage(runId, itemId));
            itemMapper.updateById(item);
            refreshTask(taskId);
            return;
        }
        int attempt = attempted.size() + 1;
        submitWithConnection(runId, task, item, connection, attempt);
    }

    private void retrySameRoute(String runId, String taskId, String itemId, String modelConfigId) {
        AiVideoTask task = taskMapper.selectById(taskId);
        AiVideoTaskItem item = itemMapper.selectById(itemId);
        if (task == null || item == null || !"VIDEO_GENERATING".equals(task.getStatus())) return;
        RuntimeModelConnection connection = videoRoutes(task).stream()
                .filter(route -> route.id().equals(modelConfigId)).findFirst().orElse(null);
        if (connection == null) {
            item.videoFail("自动重试已停止：原视频线路已停用或删除");
            itemMapper.updateById(item);
            refreshTask(taskId);
            return;
        }
        int attempt = Math.toIntExact(jobMapper.selectCount(new LambdaQueryWrapper<AiVideoGenerationJob>()
                .eq(AiVideoGenerationJob::getRunId, runId)
                .eq(AiVideoGenerationJob::getItemId, itemId))) + 1;
        submitWithConnection(runId, task, item, connection, attempt);
    }

    private void submitWithConnection(String runId, AiVideoTask task, AiVideoTaskItem item,
                                      RuntimeModelConnection connection, int attempt) {
        String taskId = task.getId();
        String itemId = item.getId();
        int duration = duration(task.getScriptJson());
        String requestedResolution = task.getResolution() == null || task.getResolution().isBlank()
                ? "720p" : task.getResolution();
        VideoGenerationProvider provider = providers.require(connection.provider());
        String resolution = provider.effectiveResolution(requestedResolution);
        AiVideoGenerationJob job = reserveSubmissionSlot(runId, task, item, attempt, connection, duration, resolution);
        if (job == null) {
            item.startVideo();
            itemMapper.updateById(item);
            return;
        }
        try {
            String prompt = task.isDirectReferenceVideo()
                    ? directReferencePrompt(item) : item.getVideoPromptText();
            GenerationRequest request = new GenerationRequest(prompt, references(task, item, connection, provider),
                    duration, task.getAspectRatio(), resolution, audioReference(task));
            paceSubmission(connection.provider());
            job.sending();
            jobMapper.updateById(job);
            SubmitResult submitted = provider.submit(connection, request);
            job.captureSubmitExchange(submitted.requestJson(), submitted.responseJson());
            job.accepted(submitted.providerTaskId(), submitted.status(), Instant.now().plusSeconds(pollSeconds(connection.provider())));
            jobMapper.updateById(job);
            item.videoQueued();
            itemMapper.updateById(item);
        } catch (VideoProviderException exception) {
            job.captureSubmitExchange(exception.getRequestJson(), exception.getResponseJson());
            if (exception.isSafeToFailover()) {
                job.failed("REJECTED", exception.getMessage());
                jobMapper.updateById(job);
                submitNext(runId, taskId, itemId);
            } else {
                job.unknown(exception.getMessage());
                jobMapper.updateById(job);
                item.videoFail("厂商可能已受理请求，已停止自动重试以避免重复扣费：" + exception.getMessage());
                itemMapper.updateById(item);
                refreshTask(taskId);
            }
        } catch (Exception exception) {
            job.failed("NOT_SENT", readable(exception));
            jobMapper.updateById(job);
            submitNext(runId, taskId, itemId);
        }
    }

    private void paceSubmission(String provider) {
        if (!"DAYAN".equals(provider)) return;
        synchronized (dayanSubmissionPacingMonitor) {
            long now = System.currentTimeMillis();
            long waitMillis = Math.max(0L, nextDayanSubmissionAtMillis - now);
            if (waitMillis > 0L) {
                try {
                    Thread.sleep(waitMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new VideoProviderException("大衍视频提交排队被中断，尚未发送请求", true);
                }
            }
            nextDayanSubmissionAtMillis = System.currentTimeMillis() + DAYAN_SUBMISSION_INTERVAL_MILLIS;
        }
    }

    private synchronized AiVideoGenerationJob reserveSubmissionSlot(String runId, AiVideoTask task, AiVideoTaskItem item,
                                                                     int attempt, RuntimeModelConnection connection,
                                                                     int duration, String resolution) {
        if (!hasSubmissionCapacity(task.getOrganizationId())) return null;
        AiVideoGenerationJob job = new AiVideoGenerationJob(runId, task.getOrganizationId(), task.getId(), item.getId(), attempt,
                connection.id(), connection.provider(), connection.modelId(), duration, task.getAspectRatio(), resolution);
        jobMapper.insert(job);
        return job;
    }

    private boolean hasSubmissionCapacity(String organizationId) {
        return jobMapper.selectCount(new LambdaQueryWrapper<AiVideoGenerationJob>()
                .eq(AiVideoGenerationJob::getOrganizationId, organizationId)
                .in(AiVideoGenerationJob::getStatus, ACTIVE_VIDEO_JOB_STATUSES)) < maxConcurrentVideoSubmissions;
    }

    public int activeVideoSubmissions(String organizationId) {
        return Math.toIntExact(jobMapper.selectCount(new LambdaQueryWrapper<AiVideoGenerationJob>()
                .eq(AiVideoGenerationJob::getOrganizationId, organizationId)
                .in(AiVideoGenerationJob::getStatus, ACTIVE_VIDEO_JOB_STATUSES)));
    }

    public int videoSubmissionLimit() {
        return maxConcurrentVideoSubmissions;
    }

    public String latestFailureMessage(String itemId) {
        return latestFailureMessage(null, itemId);
    }

    public Instant currentRunSubmittedAt(String taskId) {
        return currentItemSubmittedAt(taskId).values().stream().min(Instant::compareTo).orElse(null);
    }

    public Map<String, Instant> currentItemSubmittedAt(String taskId) {
        List<AiVideoGenerationJob> activeJobs = jobMapper.selectList(new LambdaQueryWrapper<AiVideoGenerationJob>()
                .eq(AiVideoGenerationJob::getTaskId, taskId)
                .in(AiVideoGenerationJob::getStatus, List.of("SUBMITTING", "QUEUED", "RUNNING"))
                .orderByAsc(AiVideoGenerationJob::getCreatedAt));
        if (activeJobs.isEmpty()) return Map.of();
        Map<String, Instant> result = new LinkedHashMap<>();
        for (AiVideoGenerationJob job : activeJobs) result.putIfAbsent(job.getItemId(), job.getCreatedAt());
        return result;
    }

    public Map<String, String> currentRunProviderTaskIds(String taskId) {
        List<AiVideoGenerationJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<AiVideoGenerationJob>()
                .eq(AiVideoGenerationJob::getTaskId, taskId)
                .isNotNull(AiVideoGenerationJob::getProviderTaskId)
                .orderByDesc(AiVideoGenerationJob::getCreatedAt));
        Map<String, String> result = new LinkedHashMap<>();
        for (AiVideoGenerationJob job : jobs) {
            if (job.getProviderTaskId() != null && !job.getProviderTaskId().isBlank()) {
                result.putIfAbsent(job.getItemId(), job.getProviderTaskId());
            }
        }
        return result;
    }

    private String latestFailureMessage(String runId, String itemId) {
        LambdaQueryWrapper<AiVideoGenerationJob> query = new LambdaQueryWrapper<AiVideoGenerationJob>()
                .eq(AiVideoGenerationJob::getItemId, itemId)
                .eq(AiVideoGenerationJob::getStatus, "FAILED");
        if (runId != null && !runId.isBlank()) query.eq(AiVideoGenerationJob::getRunId, runId);
        AiVideoGenerationJob latest = jobMapper.selectOne(query
                .orderByDesc(AiVideoGenerationJob::getAttemptNo)
                .orderByDesc(AiVideoGenerationJob::getUpdatedAt)
                .last("LIMIT 1"));
        if (latest == null || latest.getErrorMessage() == null || latest.getErrorMessage().isBlank()) {
            return "所有已启用的视频线路均未生成成功";
        }
        return latest.getErrorMessage().trim();
    }

    private List<Reference> references(AiVideoTask task, AiVideoTaskItem item, RuntimeModelConnection connection,
                                       VideoGenerationProvider provider) throws Exception {
        List<AiVideoTaskAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getItemId, item.getId()));
        if (task.isDirectReferenceVideo()) {
            List<AiVideoTaskAsset> references = assets.stream()
                    .filter(asset -> "VIDEO_REFERENCE_IMAGE".equals(asset.getAssetType()))
                    .sorted(Comparator.comparing(asset -> asset.getShotSequence() == null ? Integer.MAX_VALUE : asset.getShotSequence()))
                    .toList();
            if (ossStorage == null && !referenceLinks.isPubliclyReachable() && requiresPublicReferences(connection.provider())) {
                throw new VideoProviderException("当前视频线路只接收公网素材地址；部署后请设置 APP_PUBLIC_BASE_URL 为平台 HTTPS 地址", true);
            }
            if (ossStorage != null) {
                List<Reference> result = new ArrayList<>();
                for (AiVideoTaskAsset reference : references) {
                    result.add(ossReference(reference, "video-references"));
                }
                return List.copyOf(result);
            }
            if (referenceLinks.isPubliclyReachable()) {
                for (AiVideoTaskAsset reference : references) referenceDelivery.prepare(reference);
                return references.stream().map(reference -> new Reference(
                        referenceLinks.deliveryAsset(reference.getId()), reference.getContentType())).toList();
            }
            List<Reference> result = new ArrayList<>();
            for (AiVideoTaskAsset reference : references) result.add(data(reference.getFilePath(), reference.getContentType()));
            return List.copyOf(result);
        }
        AiVideoTaskAsset first = requireAsset(assets, "STORYBOARD_FIRST_FRAME");
        AiVideoTaskAsset grid = requireAsset(assets, "STORYBOARD_GRID");
        if (ossStorage == null && !referenceLinks.isPubliclyReachable() && requiresPublicReferences(connection.provider())) {
            throw new VideoProviderException("当前视频线路只接收公网素材地址；部署后请设置 APP_PUBLIC_BASE_URL 为平台 HTTPS 地址", true);
        }
        if (ossStorage != null) {
            List<Reference> result = new ArrayList<>();
            result.add(ossReference(first, "video-references"));
            result.add(ossReference(grid, "video-references"));
            result.add(ossReference(Path.of(item.getSourceFilePath()), item.getSourceContentType(), "source-images"));
            referenceVideo(task, provider, connection).ifPresent(result::add);
            return List.copyOf(result);
        }
        if (referenceLinks.isPubliclyReachable()) {
            referenceDelivery.prepare(first);
            referenceDelivery.prepare(grid);
            List<Reference> result = new ArrayList<>();
            result.add(new Reference(referenceLinks.deliveryAsset(first.getId()), "image/jpeg"));
            result.add(new Reference(referenceLinks.deliveryAsset(grid.getId()), "image/jpeg"));
            result.add(new Reference(referenceLinks.source(item.getId()), item.getSourceContentType()));
            referenceVideo(task, provider, connection).ifPresent(result::add);
            return List.copyOf(result);
        }
        return List.of(data(first.getFilePath(), first.getContentType()), data(grid.getFilePath(), grid.getContentType()),
                data(item.getSourceFilePath(), item.getSourceContentType()));
    }

    private void validateDirectReferences(String itemId) {
        List<AiVideoTaskAsset> references = assetMapper.selectList(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getItemId, itemId)
                .eq(AiVideoTaskAsset::getAssetType, "VIDEO_REFERENCE_IMAGE")
                .orderByAsc(AiVideoTaskAsset::getShotSequence));
        if (references.isEmpty()) throw new IllegalStateException("请先上传 1–9 张视频参考图");
        if (references.size() > AiVideoTaskService.MAX_VIDEO_REFERENCE_IMAGES) {
            throw new IllegalStateException("参考图不能超过 " + AiVideoTaskService.MAX_VIDEO_REFERENCE_IMAGES + " 张");
        }
        long primaryCount = references.stream().filter(AiVideoTaskAsset::isPrimaryReference).count();
        if (primaryCount != 1) {
            throw new IllegalStateException("每组必须指定且只能指定一张主参考图");
        }
        if (references.stream().anyMatch(reference -> reference.getReferenceRole() == null || reference.getReferenceRole().isBlank())) {
            throw new IllegalStateException("请先为每张参考图指定用途");
        }
    }

    private String directReferencePrompt(AiVideoTaskItem item) {
        List<AiVideoTaskAsset> references = assetMapper.selectList(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getItemId, item.getId())
                .eq(AiVideoTaskAsset::getAssetType, "VIDEO_REFERENCE_IMAGE")
                .orderByAsc(AiVideoTaskAsset::getShotSequence));
        StringBuilder prompt = new StringBuilder("【参考图职责】编号仅对应图片发送顺序。按指定职责引用商品、人物、动作或场景；不得把编号、边框、水印、拼图布局及无关背景复制到成片中。\n");
        for (AiVideoTaskAsset reference : references) {
            int order = reference.getShotSequence() == null ? references.indexOf(reference) + 1 : reference.getShotSequence();
            prompt.append("图").append(order).append('：')
                    .append(reference.isPrimaryReference() ? "主｜" : "辅｜")
                    .append(referenceRoleName(reference.getReferenceRole()));
            if (reference.getReferenceInstruction() != null && !reference.getReferenceInstruction().isBlank()) {
                String instruction = reference.getReferenceInstruction().replaceAll("\\s+", " ").trim();
                prompt.append("｜").append(instruction);
            }
            prompt.append('\n');
        }
        prompt.append("各图只在指定职责内生效；冲突时主参考优先，专项图仅覆盖对应职责。\n");
        prompt.append("【执行】\n").append(item.getVideoPromptText().trim());
        return prompt.toString();
    }

    private String referenceRoleName(String role) {
        return switch (role == null ? "" : role) {
            case "APPEARANCE" -> "商品或主体外观";
            case "DETAIL" -> "细节与材质";
            case "ANGLE" -> "侧面、背面或特定角度";
            case "CHARACTER" -> "人物身份与一致性";
            case "SCENE" -> "环境、布景与光线风格";
            case "ACTION" -> "动作、姿态与运镜";
            case "END_FRAME" -> "结尾画面与收尾构图";
            case "OTHER" -> "用户自定义补充";
            default -> "未指定";
        };
    }

    private java.util.Optional<Reference> referenceVideo(AiVideoTask task, VideoGenerationProvider provider,
                                                         RuntimeModelConnection connection) {
        if (!task.isUseScriptReferenceVideo()
                || !"PRODUCT_ONLY".equals(task.getGenerationMode())
                || !provider.supportsReferenceVideo(connection)
                || task.getScriptId() == null || task.getScriptId().isBlank()) {
            return java.util.Optional.empty();
        }
        VideoScript script = scriptMapper.selectById(task.getScriptId());
        if (script == null || !"VIDEO_ANALYSIS".equals(script.getSourceType())
                || script.getSourceFilePath() == null || script.getSourceContentType() == null
                || !script.getSourceContentType().startsWith("video/")
                || !Files.isRegularFile(Path.of(script.getSourceFilePath()))) {
            return java.util.Optional.empty();
        }
        if (ossStorage != null) {
            try {
                return java.util.Optional.of(ossReference(Path.of(script.getSourceFilePath()),
                        script.getSourceContentType(), "script-videos"));
            } catch (Exception exception) {
                throw new IllegalStateException("无法把参考视频发布到 OSS", exception);
            }
        }
        return java.util.Optional.of(new Reference(referenceLinks.scriptSource(script.getId()), script.getSourceContentType()));
    }

    private Reference data(String filePath, String mediaType) throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of(filePath));
        return new Reference("data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes), mediaType);
    }

    private Reference ossReference(Path path, String mediaType, String category) throws Exception {
        return new Reference(ossStorage.publish(path, mediaType, category), mediaType);
    }

    private Reference ossReference(AiVideoTaskAsset asset, String category) throws Exception {
        if (ossStorage.isOssLocation(asset.getFilePath())) {
            return new Reference(ossStorage.signedUrl(asset.getFilePath()), asset.getContentType());
        }
        VideoReferenceDeliveryService.Delivery prepared = referenceDelivery.prepare(asset);
        return ossReference(prepared.path(), prepared.contentType(), category);
    }

    private String audioReference(AiVideoTask task) {
        if (!"UPLOADED".equals(task.getMusicMode()) || task.getMusicFilePath() == null) return null;
        Path path = Path.of(task.getMusicFilePath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalStateException("已上传的配乐文件不存在");
        if (ossStorage != null) {
            try {
                return ossStorage.publish(path, task.getMusicContentType(), "task-audio");
            } catch (Exception exception) {
                throw new IllegalStateException("无法把配乐发布到 OSS", exception);
            }
        }
        if (!referenceLinks.isPubliclyReachable()) {
            throw new VideoProviderException("已上传配乐需要平台 HTTPS 公网地址才能发送给视频厂商", true);
        }
        return referenceLinks.taskAudio(task.getId());
    }

    private AiVideoTaskAsset requireAsset(List<AiVideoTaskAsset> assets, String type) {
        return assets.stream().filter(asset -> type.equals(asset.getAssetType())).max(Comparator.comparing(AiVideoTaskAsset::getCreatedAt))
                .orElseThrow(() -> new IllegalStateException("缺少 " + type + " 素材"));
    }

    @Scheduled(fixedDelay = 5000)
    public void pollDueJobs() {
        List<AiVideoGenerationJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<AiVideoGenerationJob>()
                .in(AiVideoGenerationJob::getStatus, List.of("QUEUED", "RUNNING"))
                .le(AiVideoGenerationJob::getNextPollAt, Instant.now())
                .orderByAsc(AiVideoGenerationJob::getNextPollAt));
        Instant now = Instant.now();
        jobs.forEach(job -> {
            if (jobMapper.claimPoll(job.getId(), now, now.plusSeconds(90)) == 1) {
                executor.execute(() -> poll(job.getId()));
            }
        });
    }

    private void poll(String jobId) {
        AiVideoGenerationJob job = jobMapper.selectById(jobId);
        if (job == null || !List.of("QUEUED", "RUNNING").contains(job.getStatus())) return;
        RuntimeModelConnection connection = modelConfigs.connectionById(job.getOrganizationId(), job.getModelConfigId()).orElse(null);
        if (connection == null) return;
        boolean providerCompleted = false;
        try {
            QueryResult result = providers.require(job.getProvider()).query(connection, job.getProviderTaskId());
            job.captureQueryExchange(result.requestJson(), result.responseJson());
            if ("SUCCEEDED".equals(result.status())) {
                providerCompleted = true;
                if (result.outputUrl() == null || result.outputUrl().isBlank()) {
                    job.running("RUNNING", Instant.now().plusSeconds(pollSeconds(job.getProvider())));
                    jobMapper.updateById(job);
                    return;
                }
                GeneratedVideo output = download(job, connection, providers.require(job.getProvider()), result.outputUrl());
                replaceVideoAsset(job, output);
                job.succeeded(result.outputUrl(), result.cost(), json(result.usage()));
                jobMapper.updateById(job);
                AiVideoTaskItem item = itemMapper.selectById(job.getItemId());
                if (item != null) { item.videoComplete(); itemMapper.updateById(item); }
                refreshTask(job.getTaskId());
            } else if ("FAILED".equals(result.status())) {
                job.failed("ACCEPTED", result.errorMessage() == null || result.errorMessage().isBlank() ? "厂商生成失败" : result.errorMessage());
                jobMapper.updateById(job);
                submitNext(job.getRunId(), job.getTaskId(), job.getItemId());
            } else {
                job.running(result.status(), Instant.now().plusSeconds(pollSeconds(job.getProvider())));
                jobMapper.updateById(job);
                AiVideoTaskItem item = itemMapper.selectById(job.getItemId());
                if (item != null) {
                    item.videoRunning(providerProgress(result.usage().get("progress")));
                    itemMapper.updateById(item);
                }
                refreshTask(job.getTaskId());
            }
        } catch (Exception exception) {
            if (exception instanceof VideoProviderException providerException) {
                job.captureQueryExchange(providerException.getRequestJson(), providerException.getResponseJson());
            }
            String message = providerCompleted
                    ? "厂商已完成，视频回传待重试：" + readable(exception)
                    : "查询厂商任务待重试：" + readable(exception);
            job.retryDownload(message,
                    Instant.now().plusSeconds(pollSeconds(job.getProvider())));
            jobMapper.updateById(job);
        }
    }

    private Integer providerProgress(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return null;
        try { return new BigDecimal(String.valueOf(value)).intValue(); }
        catch (Exception ignored) { return null; }
    }

    private GeneratedVideo download(AiVideoGenerationJob job, RuntimeModelConnection connection,
                                    VideoGenerationProvider provider, String url) throws Exception {
        AiVideoTaskItem item = itemMapper.selectById(job.getItemId());
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET();
        if (provider.requiresAuthenticatedDownload()) {
            requestBuilder.header("Authorization", "Bearer " + connection.apiKey());
        }
        HttpRequest request = requestBuilder.build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            response.body().close();
            throw new IllegalStateException("下载厂商视频失败：HTTP " + response.statusCode());
        }
        long declaredLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
        if (ossStorage != null) {
            String category = "generated-videos/" + job.getOrganizationId() + "/" + job.getTaskId()
                    + "/" + job.getItemId();
            try (InputStream input = response.body()) {
                OssStorageService.StoredObject stored = ossStorage.store(input, declaredLength, MAX_VIDEO_BYTES,
                        "video/mp4", category, "video-result.mp4");
                return new GeneratedVideo(stored.location(), stored.size());
            }
        }
        Path directory = Path.of(item.getSourceFilePath()).toAbsolutePath().normalize().getParent();
        Files.createDirectories(directory);
        Path temporary = directory.resolve("video-" + job.getId() + ".part");
        Path output = directory.resolve("video-result.mp4");
        try (InputStream input = response.body(); var target = Files.newOutputStream(temporary)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            for (int read; (read = input.read(buffer)) >= 0;) {
                total += read;
                if (total > MAX_VIDEO_BYTES) throw new IllegalStateException("厂商视频超过 500MB 限制");
                target.write(buffer, 0, read);
            }
        }
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        return new GeneratedVideo(output.toString(), Files.size(output));
    }

    private void replaceVideoAsset(AiVideoGenerationJob job, GeneratedVideo output) throws Exception {
        List<AiVideoTaskAsset> previous = assetMapper.selectList(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getItemId, job.getItemId()).eq(AiVideoTaskAsset::getAssetType, "VIDEO_RESULT"));
        previous.forEach(asset -> {
            assetMapper.deleteById(asset.getId());
            if (ossStorage != null && ossStorage.isOssLocation(asset.getFilePath())) ossStorage.delete(asset.getFilePath());
        });
        assetMapper.insert(new AiVideoTaskAsset(job.getOrganizationId(), job.getTaskId(), job.getItemId(), "VIDEO_RESULT",
                1, output.location(), "video/mp4", output.size(), null, job.getModelId()));
    }

    private synchronized void refreshTask(String taskId) {
        AiVideoTask task = taskMapper.selectById(taskId);
        if (task == null || !"VIDEO_GENERATING".equals(task.getStatus())) return;
        List<AiVideoTaskItem> items = itemMapper.selectList(new LambdaQueryWrapper<AiVideoTaskItem>().eq(AiVideoTaskItem::getTaskId, taskId));
        int succeeded = (int) items.stream().filter(item -> "VIDEO_READY".equals(item.getStatus())).count();
        List<AiVideoTaskItem> failed = items.stream().filter(item -> "VIDEO_FAILED".equals(item.getStatus())).toList();
        int terminal = succeeded + failed.size();
        boolean automaticRetryPending = failed.stream().anyMatch(this::hasAutomaticRetryPending);
        if (terminal == items.size() && !automaticRetryPending) {
            if (succeeded == 0) task.fail(failed.stream().map(AiVideoTaskItem::getErrorMessage).filter(value -> value != null).findFirst().orElse("视频生成失败"));
            else task.completeVideos(succeeded, failed.isEmpty() ? null : failed.size() + " 款视频生成失败，可单独重试");
        } else {
            task.reportProgress(Math.max(2, terminal * 100 / Math.max(1, items.size())), succeeded);
        }
        taskMapper.updateById(task);
    }

    @SuppressWarnings("unchecked")
    private int duration(String scriptJson) {
        try {
            Map<String, Object> root = objectMapper.readValue(scriptJson, Map.class);
            Object raw = root.get("shots");
            double max = 0;
            if (raw instanceof List<?> shots) for (Object value : shots) if (value instanceof Map<?, ?> shot) {
                Object end = shot.get("endSecond");
                if (end != null) max = Math.max(max, Double.parseDouble(String.valueOf(end)));
            }
            return Math.max(4, Math.min(15, (int) Math.ceil(max)));
        } catch (Exception ignored) { return 12; }
    }

    private int pollSeconds(String provider) { return "DAYAN".equals(provider) ? 20 : 8; }
    private boolean requiresPublicReferences(String provider) { return "DAYAN".equals(provider) || "FAST".equals(provider); }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); } catch (Exception ignored) { return "{}"; } }
    private String readable(Throwable value) { return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage(); }
    private record GeneratedVideo(String location, long size) {}
}
