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
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class AiVideoGenerationService {
    private static final long MAX_VIDEO_BYTES = 500L * 1024 * 1024;
    private static final long DAYAN_SUBMISSION_INTERVAL_MILLIS = 500L;
    private static final int MAX_AUTOMATIC_TRANSIENT_RETRIES = 3;
    private static final int MAX_GRID_REGENERATIONS = 2;
    static final String GRID_REJECTED = "VIDEO_GRID_REJECTED: ";
    static final String REVIEW_PAUSED = "VIDEO_REVIEW_PAUSED: ";
    private static final String INTERRUPTED_NOT_SENT = "提交服务重启中断，厂商未返回任务 ID；系统已自动重新提交";
    private static final long[] AUTOMATIC_RETRY_DELAYS_SECONDS = {30L, 90L, 300L};
    private static final List<String> ACTIVE_VIDEO_JOB_STATUSES = List.of("SUBMITTING", "QUEUED", "RUNNING", "REVIEWING");
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
    private final Set<String> pollingJobs = ConcurrentHashMap.newKeySet();
    private VideoFirstFrameAuditService firstFrameAudit;
    private VideoReviewFinalizer reviewFinalizer;
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

    @Autowired
    public void setFirstFrameAudit(VideoFirstFrameAuditService firstFrameAudit) {
        this.firstFrameAudit = firstFrameAudit;
    }

    @Autowired
    public void setReviewFinalizer(VideoReviewFinalizer reviewFinalizer) {
        this.reviewFinalizer = reviewFinalizer;
    }

    @Transactional
    public AiVideoTask start(AiVideoTask task) {
        List<RuntimeModelConnection> routes = videoRoutes(task);
        if (routes.isEmpty()) throw new IllegalStateException(missingRouteMessage(task));
        RuntimeModelConnection primary = routes.getFirst();
        if ("AUTO".equals(primary.provider())) throw new IllegalStateException("无法识别视频厂商，请编辑视频模型并选择厂商");
        validateIndividualFrameRoute(task, primary);
        List<AiVideoTaskItem> allItems = itemMapper.selectList(new LambdaQueryWrapper<AiVideoTaskItem>()
                .eq(AiVideoTaskItem::getTaskId, task.getId()).orderByAsc(AiVideoTaskItem::getCreatedAt));
        if (allItems.isEmpty()) throw new IllegalStateException("请先上传并生成商品图片");
        List<AiVideoTaskItem> items = allItems.stream().filter(item -> !hasVideoResult(item.getId())).toList();
        if (items.isEmpty()) throw new IllegalStateException("当前任务的全部视频已经生成，无需重复提交");
        for (AiVideoTaskItem item : items) {
            if (task.isDirectReferenceVideo()) {
                validateDirectReferences(task, item.getId());
                if (AiVideoTaskService.isScriptVideoReplacement(task)) {
                    validateReplacementMappings(item.getId());
                }
            }
            else if (!AiVideoTaskService.isScriptVideoReplacement(task) && !hasRequiredImages(item.getId())) {
                throw new IllegalStateException(item.getName() + " 的首帧或九宫格尚未生成");
            }
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
        if (!AiVideoTaskService.isScriptVideoReplacement(task) && !hasRequiredImages(itemId)) return false;
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
            if (task == null || !"VIDEO_GENERATING".equals(task.getStatus()) || hasCurrentVideoResult(item.getId())) continue;
            if (!hasSubmissionCapacity(task.getOrganizationId())) continue;
            long activeJobs = jobMapper.selectCount(new LambdaQueryWrapper<AiVideoGenerationJob>()
                    .eq(AiVideoGenerationJob::getItemId, item.getId())
                    .in(AiVideoGenerationJob::getStatus, ACTIVE_VIDEO_JOB_STATUSES));
            if (activeJobs == 0) {
                AiVideoGenerationJob latest = latestJob(item.getId());
                if (isGridRetryContinuation(latest)) queueGridRetry(latest);
                else queueSubmission(UUID.randomUUID().toString(), task.getId(), item.getId());
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
                    || !"VIDEO_SUBMITTING".equals(item.getStatus()) || hasCurrentVideoResult(item.getId())) continue;
            if (!hasSubmissionCapacity(task.getOrganizationId()) || !queuedSubmissions.add(item.getId())) continue;
            job.failed("NOT_SENT", INTERRUPTED_NOT_SENT);
            jobMapper.updateById(job);
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
        if (message != null && (message.startsWith(GRID_REJECTED) || message.startsWith(REVIEW_PAUSED))) return false;
        return retryableVideoConcurrencyFailure(message);
    }

    static boolean retryableVideoConcurrencyFailure(String message) {
        if (message == null) return false;
        String value = message.toLowerCase();
        return value.startsWith("连接厂商失败，请求尚未发送：")
                || value.contains("（429）") || value.contains("(429)")
                || value.contains("（502）") || value.contains("(502)")
                || value.contains("（503）") || value.contains("(503)")
                || value.contains("（504）") || value.contains("(504)")
                || value.contains("生成服务暂不可用")
                || value.contains("temporarily unavailable")
                || value.contains("并发限制") || value.contains("并发已满")
                || value.contains("限流") || value.contains("concurrency limit exceeded")
                || value.contains("too many concurrent") || value.contains("rate limit")
                || value.contains("too many requests")
                // The persisted references are still valid. A retry issues fresh delivery URLs,
                // so a temporary provider-side reference fetch must not require a re-upload.
                || value.contains("参考图片获取失败")
                || value.contains("参考图片读取失败")
                || value.contains("参考图片下载失败")
                || value.contains("素材暂时无法读取")
                || value.contains("failed to fetch reference image")
                || value.contains("failed to download reference image")
                || value.contains("reference image") && (value.contains("fetch failed")
                        || value.contains("download failed") || value.contains("unavailable"));
    }

    private boolean hasAutomaticRetryPending(AiVideoTaskItem item) {
        AiVideoGenerationJob latest = latestJob(item.getId());
        if (isGridRejection(latest)) return gridRejectionCount(latest.getRunId(), item.getId()) <= MAX_GRID_REGENERATIONS;
        if (latest == null || !"FAILED".equals(latest.getStatus())
                || !isTransientSubmitFailure(latest.getErrorMessage())) return false;
        return transientFailureCount(latest.getRunId(), item.getId(), latest.getModelConfigId())
                <= MAX_AUTOMATIC_TRANSIENT_RETRIES;
    }

    private boolean isGridRejection(AiVideoGenerationJob job) {
        return job != null && "FAILED".equals(job.getStatus()) && job.getErrorMessage() != null
                && job.getErrorMessage().startsWith(GRID_REJECTED);
    }

    private boolean isGridRetryContinuation(AiVideoGenerationJob job) {
        return isGridRejection(job) || job != null && "FAILED".equals(job.getStatus())
                && "NOT_SENT".equals(job.getRequestState()) && INTERRUPTED_NOT_SENT.equals(job.getErrorMessage())
                && gridRejectionCount(job.getRunId(), job.getItemId()) > 0;
    }

    private int gridRejectionCount(String runId, String itemId) {
        return Math.toIntExact(jobMapper.selectCount(new LambdaQueryWrapper<AiVideoGenerationJob>()
                .eq(AiVideoGenerationJob::getRunId, runId).eq(AiVideoGenerationJob::getItemId, itemId)
                .eq(AiVideoGenerationJob::getStatus, "FAILED")
                .likeRight(AiVideoGenerationJob::getErrorMessage, GRID_REJECTED)));
    }

    @Scheduled(initialDelay = 10_000, fixedDelay = 10_000)
    public void retryGridRejectedVideos() {
        for (AiVideoTaskItem item : itemMapper.selectList(new LambdaQueryWrapper<AiVideoTaskItem>()
                .eq(AiVideoTaskItem::getStatus, "VIDEO_FAILED"))) {
            AiVideoGenerationJob latest = latestJob(item.getId());
            if (isGridRejection(latest)) queueGridRetry(latest);
        }
    }

    private void queueGridRetry(AiVideoGenerationJob rejected) {
        if (gridRejectionCount(rejected.getRunId(), rejected.getItemId()) > MAX_GRID_REGENERATIONS) return;
        AiVideoTask task = taskMapper.selectById(rejected.getTaskId());
        if (task == null || !"VIDEO_GENERATING".equals(task.getStatus())
                || !hasSubmissionCapacity(task.getOrganizationId()) || hasCurrentVideoResult(rejected.getItemId())) return;
        if (jobMapper.selectCount(new LambdaQueryWrapper<AiVideoGenerationJob>()
                .eq(AiVideoGenerationJob::getItemId, rejected.getItemId())
                .in(AiVideoGenerationJob::getStatus, ACTIVE_VIDEO_JOB_STATUSES)) > 0) return;
        if (videoRoutes(task).stream().noneMatch(route -> route.id().equals(rejected.getModelConfigId()))) {
            rejected.failed("ACCEPTED", REVIEW_PAUSED + "首帧不合格，原视频线路已停用，未再次扣费生成");
            jobMapper.updateById(rejected);
            AiVideoTaskItem item = itemMapper.selectById(rejected.getItemId());
            if (item != null) { item.videoFail(rejected.getErrorMessage()); itemMapper.updateById(item); }
            refreshTask(rejected.getTaskId());
            return;
        }
        if (!queuedSubmissions.add(rejected.getItemId())) return;
        executor.execute(() -> {
            try {
                AiVideoGenerationJob latest = latestJob(rejected.getItemId());
                if (latest != null && latest.getId().equals(rejected.getId()) && isGridRetryContinuation(latest)) {
                    retrySameRoute(rejected.getRunId(), rejected.getTaskId(), rejected.getItemId(), rejected.getModelConfigId());
                }
            } finally { queuedSubmissions.remove(rejected.getItemId()); }
        });
    }

    static String strengthenSingleScenePrompt(String prompt, int rejectionCount) {
        if (rejectionCount <= 0) return prompt;
        return "【首帧画面纠正｜第" + rejectionCount + "次自动重做】\n"
                + "上次输出的第一帧出现多个画面，未通过审核。这次从第一帧开始只拍摄一个铺满画布的真实场景，"
                + "画面中仅有同一位人物或同一件商品。第一张参考图定义唯一的起始场景，其他参考只提供随后发生的动作。"
                + "所有动作按时间先后执行，任意时刻都只呈现其中一个场景，保持完整画幅。"
                + "严禁把参考图集合、九宫格、拼图、格线、分屏或画中画拍进视频；不得先出现拼图再放大其中一格。"
                + "镜头始终处于人物所在的真实空间内，不展示图片、纸张、屏幕或相册。原有动作时间轴、商品结构和声音要求继续执行。\n\n"
                + (prompt == null ? "" : prompt);
    }

    private boolean hasVideoResult(String itemId) {
        return assetMapper.selectCount(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getItemId, itemId)
                .eq(AiVideoTaskAsset::getAssetType, "VIDEO_RESULT")) > 0;
    }

    private boolean hasCurrentVideoResult(String itemId) {
        if (!hasVideoResult(itemId)) return false;
        AiVideoGenerationJob latest = latestJob(itemId);
        return latest == null || "SUCCEEDED".equals(latest.getStatus());
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
        int attempt = Math.toIntExact(jobMapper.selectCount(new LambdaQueryWrapper<AiVideoGenerationJob>()
                .eq(AiVideoGenerationJob::getRunId, runId).eq(AiVideoGenerationJob::getItemId, itemId))) + 1;
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
        int duration = task.isGeneral() ? task.getDurationSeconds() : duration(task.getScriptJson());
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
        boolean qualityRetry = gridRejectionCount(runId, itemId) > 0;
        try {
            String prompt = task.isGeneral() ? generalReferencePrompt(item.getVideoPromptText())
                    : task.isDirectReferenceVideo() ? directReferencePrompt(task, item) : item.getVideoPromptText();
            if (!task.isGeneral()) {
                if (!task.isDirectReferenceVideo()) prompt = usesIndividualFrames(task) ? individualFramesSafetyPrompt(prompt, task.getAspectRatio()) : gridStoryboardSafetyPrompt(prompt, task.getAspectRatio());
                prompt = finalAudioPerformancePrompt(prompt, task, objectMapper);
            }
            prompt = strengthenSingleScenePrompt(prompt, gridRejectionCount(runId, itemId));
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
                job.failed("REJECTED", qualityRetry ? REVIEW_PAUSED + "首帧重做提交失败：" + exception.getMessage()
                        : exception.getMessage());
                jobMapper.updateById(job);
                if (qualityRetry) {
                    item.videoFail(job.getErrorMessage()); itemMapper.updateById(item); refreshTask(taskId);
                } else submitNext(runId, taskId, itemId);
            } else {
                job.unknown(exception.getMessage());
                jobMapper.updateById(job);
                item.videoFail("厂商可能已受理请求，已停止自动重试以避免重复扣费：" + exception.getMessage());
                itemMapper.updateById(item);
                refreshTask(taskId);
            }
        } catch (Exception exception) {
            job.failed("NOT_SENT", qualityRetry ? REVIEW_PAUSED + "首帧重做准备失败，未切换线路：" + readable(exception)
                    : readable(exception));
            jobMapper.updateById(job);
            if (qualityRetry) {
                item.videoFail(job.getErrorMessage()); itemMapper.updateById(item); refreshTask(taskId);
            } else submitNext(runId, taskId, itemId);
        }
    }

    static String generalReferencePrompt(String prompt) {
        return String.valueOf(prompt) + "\n\n素材编号：@图片1、@图片2…按本请求中图片素材的顺序对应；@视频1对应唯一的视频素材。请按提示词指定的各素材用途生成，不把素材名称或编号画进成片。";
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
                .in(AiVideoGenerationJob::getStatus, ACTIVE_VIDEO_JOB_STATUSES)
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
        if (AiVideoTaskService.isScriptVideoReplacement(task)) {
            if (ossStorage == null && !referenceLinks.isPubliclyReachable()
                    && requiresPublicReferences(connection.provider())) {
                throw new VideoProviderException("当前视频线路只接收公网素材地址；部署后请设置 APP_PUBLIC_BASE_URL 为平台 HTTPS 地址", true);
            }
            List<Reference> result = new ArrayList<>();
            List<AiVideoTaskAsset> replacementObjects = assets.stream()
                    .filter(asset -> "VIDEO_REFERENCE_IMAGE".equals(asset.getAssetType()))
                    .sorted(Comparator.comparing(asset -> asset.getShotSequence() == null
                            ? Integer.MAX_VALUE : asset.getShotSequence()))
                    .toList();
            if (!replacementObjects.isEmpty() && ossStorage != null) {
                for (AiVideoTaskAsset replacementObject : replacementObjects) {
                    result.add(ossReference(replacementObject, "video-references"));
                }
            } else if (!replacementObjects.isEmpty() && referenceLinks.isPubliclyReachable()) {
                for (AiVideoTaskAsset replacementObject : replacementObjects) {
                    referenceDelivery.prepare(replacementObject);
                    result.add(new Reference(referenceLinks.deliveryAsset(replacementObject.getId()),
                            replacementObject.getContentType()));
                }
            } else if (!replacementObjects.isEmpty()) {
                for (AiVideoTaskAsset replacementObject : replacementObjects) {
                    result.add(data(replacementObject.getFilePath(), replacementObject.getContentType()));
                }
            } else if (ossStorage != null) {
                // Compatibility for replacement tasks created before multi-object groups existed.
                result.add(ossReference(Path.of(item.getSourceFilePath()), item.getSourceContentType(), "source-images"));
            } else if (referenceLinks.isPubliclyReachable()) {
                result.add(new Reference(referenceLinks.source(item.getId()), item.getSourceContentType()));
            } else {
                result.add(data(item.getSourceFilePath(), item.getSourceContentType()));
            }
            result.add(referenceVideo(task, provider, connection)
                    .orElseThrow(() -> new VideoProviderException("当前视频线路不支持原视频定向替换，请更换支持参考视频的线路", true)));
            return List.copyOf(result);
        }
        if (task.isGeneral()) {
            List<AiVideoTaskAsset> media = assets.stream().filter(asset ->
                    "VIDEO_REFERENCE_IMAGE".equals(asset.getAssetType()) || "VIDEO_REFERENCE_VIDEO".equals(asset.getAssetType()))
                    .sorted(Comparator.comparing(asset -> asset.getShotSequence() == null ? Integer.MAX_VALUE : asset.getShotSequence())).toList();
            long videoCount = media.stream().filter(asset -> "VIDEO_REFERENCE_VIDEO".equals(asset.getAssetType())).count();
            if (videoCount > 1) throw new VideoProviderException("参考视频最多 1 条", true);
            if (videoCount > 0 && !provider.supportsReferenceVideo(connection)) throw new VideoProviderException("当前线路不支持参考视频，请使用质量线路", true);
            List<Reference> result = new ArrayList<>();
            for (AiVideoTaskAsset asset : media) {
                if (ossStorage != null) result.add("VIDEO_REFERENCE_VIDEO".equals(asset.getAssetType())
                        ? ossReference(Path.of(asset.getFilePath()), asset.getContentType(), "video-references")
                        : ossReference(asset, "video-references"));
                else if (referenceLinks.isPubliclyReachable()) result.add(new Reference(referenceLinks.asset(asset.getId()), asset.getContentType()));
                else if (requiresPublicReferences(connection.provider())) throw new VideoProviderException("当前线路需要公网素材地址", true);
                else result.add(data(asset.getFilePath(), asset.getContentType()));
            }
            return List.copyOf(result);
        }
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
        if (usesIndividualFrames(task)) return individualFrameReferences(task, assets, connection);
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

    static boolean usesIndividualFrames(AiVideoTask task) {
        return !task.isDirectReferenceVideo() && "INDIVIDUAL_FRAMES".equals(task.getVideoReferenceMode());
    }

    static void validateIndividualFrameRoute(AiVideoTask task, RuntimeModelConnection connection) {
        if (usesIndividualFrames(task) && !("DAYAN".equalsIgnoreCase(connection.provider())
                && "minimax-h3".equalsIgnoreCase(connection.modelId()))) {
            throw new IllegalStateException("九张独立分镜参考仅支持 DAYAN/minimax-h3，当前线路不支持；未提交视频请求");
        }
    }

    private List<Reference> individualFrameReferences(AiVideoTask task, List<AiVideoTaskAsset> assets,
                                                     RuntimeModelConnection connection) throws Exception {
        validateIndividualFrameRoute(task, connection);
        if (ossStorage == null && !referenceLinks.isPubliclyReachable()) throw new IllegalStateException("九张独立分镜参考需要配置公网签名素材地址或 OSS；未提交视频请求");
        AiVideoTaskAsset grid = requireAsset(assets, "STORYBOARD_GRID");
        byte[] original;
        final int maximumBytes = 64 * 1024 * 1024;
        if (ossStorage != null && ossStorage.isOssLocation(grid.getFilePath())) {
            OssStorageService.Download download = ossStorage.download(grid.getFilePath());
            if (download.size() > maximumBytes) throw new IllegalStateException("九宫格原图超过 64MB");
            try (InputStream input = download.resource().getInputStream()) { original = input.readNBytes(maximumBytes + 1); }
        } else {
            Path source = Path.of(grid.getFilePath());
            if (Files.size(source) > maximumBytes) throw new IllegalStateException("九宫格原图超过 64MB");
            original = Files.readAllBytes(source);
        }
        if (original.length == 0 || original.length > maximumBytes) throw new IllegalStateException("九宫格原图大小无效");
        List<VideoReferenceDeliveryService.Delivery> frames = referenceDelivery.prepareStoryboardFrames(original);
        if (frames.size() != 9) throw new IllegalStateException("独立分镜参考必须恰好九张；未提交视频请求");
        List<Reference> result = new ArrayList<>(9);
        for (VideoReferenceDeliveryService.Delivery frame : frames) {
            result.add(ossStorage != null
                    ? ossReference(frame.path(), frame.contentType(), "video-storyboard-frames")
                    : new Reference(referenceLinks.storyboardFrame(grid.getId(), result.size() + 1), frame.contentType()));
        }
        return List.copyOf(result);
    }

    static String individualFramesSafetyPrompt(String prompt, String aspectRatio) {
        String ratio = aspectRatio == null || aspectRatio.isBlank() ? "请求画幅" : aspectRatio.trim();
        return "【九张独立分镜参考协议】\n"
                + "本次依次提供九张独立单画面，图01至图09分别对应后附完整时间轴的01至09姿态节点，编号只表示时间顺序。图01是完整首帧；图02至图09只提供对应时刻的动作、朝向、场景和机位，不能同时显示。\n"
                + "从图01的单画面开始，依时间轴连续经过全部九个姿态。人物、商品身份与后附服装结构契约全程保持一致；每帧始终只有一个铺满" + ratio + "画布的场景。\n"
                + "参考图不是需要逐张展示的照片，不生成拼贴、分屏、九宫格、缩略图、边框或编号。转场只服从时间轴明确的真实切点，同镜头内保持自然连续运动。\n\n"
                + (prompt == null ? "" : prompt);
    }

    static String gridStoryboardSafetyPrompt(String prompt, String aspectRatio) {
        String outputRatio = aspectRatio == null || aspectRatio.isBlank() ? "请求画幅" : aspectRatio.trim();
        return "【九宫格参考协议】\n"
                + "图1锁定首帧；图2是按左到右、上到下读取01→09的时间索引；图3锁定完整商品身份。采用图2每格的动作与场景意图，但只执行后附时间轴已归一化的动作、景别与转场，再重建为单画面。\n"
                + "成片每一帧只能有一个铺满" + outputRatio + "画布的场景。禁止输出九宫格布局、画中画、分屏、拼贴、缩略图或格线；转场中也不得闪现完整或局部宫格。\n"
                + VideoTimelineSemantics.TRANSITION_POLICY + "\n"
                + "人物身体最多两条手臂和两只手；被遮挡或出画的肢体无需补出，纯商品镜头可以无人无手。每只可见手必须自然连接到唯一手臂。\n\n"
                + (prompt == null ? "" : prompt);
    }

    static String finalAudioPerformancePrompt(String prompt, AiVideoTask task, ObjectMapper objectMapper) {
        boolean hasVoiceover = AiVideoTaskService.hasVoiceover(task, objectMapper);
        String market = task.getTargetMarket() == null || task.getTargetMarket().isBlank()
                ? "脚本文案所用语言" : "目标市场“" + task.getTargetMarket().trim() + "”对应的本地语言";
        String music = "UPLOADED".equals(task.getMusicMode())
                ? "已上传配乐是唯一BGM，必须从0秒贯穿结尾，完整保留清晰旋律和稳定节拍"
                : "必须生成从0秒贯穿结尾、具有清晰可辨主旋律和稳定节拍的纯器乐伴奏；不得静音，也不得仅有无旋律环境底噪";
        String voice = hasVoiceover
                ? "只允许按时间轴生成与画面人物分离的画外旁白。旁白使用" + market
                    + "，逐字朗读给定原文，发音自然清晰；禁止增删、改写、换语言、混入乱码、无意义音节、含糊拟声、歌声、哼唱或呢喃。旁白时只适度压低BGM，旁白结束立即恢复，伴奏不可消失"
                : "时间轴没有旁白，因此全片禁止对白、旁白、口播、歌声、哼唱、呢喃、乱码或任何无意义人声；音轨只允许纯器乐BGM和必要的自然环境声";
        String base = prompt == null ? "" : prompt.trim();
        return base + "\n\n【最终音画硬约束｜最高优先级并覆盖前文】\n"
                + "所有出镜人物全程保持嘴唇自然闭合、下颌稳定，不说话、不口播、不唱歌、不念台词，也不做任何对口型动作。"
                + "脚本或自定义提示中的“对白、台词、口播、讲解、旁白、narration、speak、talk、lip sync”一律只作画外旁白语义，绝不由出镜人物演绎，且不得驱动嘴唇、表情、头部、手势或身体节拍。\n"
                + music + "；不得包含歌词、演唱或含糊人声。\n"
                + voice + "。与前文任何要求冲突时，只执行本段。";
    }

    private void validateDirectReferences(AiVideoTask task, String itemId) {
        if (task.isGeneral()) {
            List<AiVideoTaskAsset> all = assetMapper.selectList(new LambdaQueryWrapper<AiVideoTaskAsset>()
                    .eq(AiVideoTaskAsset::getItemId, itemId));
            long images = all.stream().filter(a -> "VIDEO_REFERENCE_IMAGE".equals(a.getAssetType())).count();
            long videos = all.stream().filter(a -> "VIDEO_REFERENCE_VIDEO".equals(a.getAssetType())).count();
            if (images + videos == 0 || images > 9 || videos > 1) throw new IllegalStateException("请上传最多 9 张图片和 1 条视频参考");
            return;
        }
        List<AiVideoTaskAsset> references = assetMapper.selectList(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getItemId, itemId)
                .eq(AiVideoTaskAsset::getAssetType, "VIDEO_REFERENCE_IMAGE")
                .orderByAsc(AiVideoTaskAsset::getShotSequence));
        if (references.isEmpty()) throw new IllegalStateException("请先上传 1–9 张视频参考图");
        if (references.size() > AiVideoTaskService.MAX_VIDEO_REFERENCE_IMAGES) {
            throw new IllegalStateException("参考图不能超过 " + AiVideoTaskService.MAX_VIDEO_REFERENCE_IMAGES + " 张");
        }
        if (task.isGeneral()) return;
        long primaryCount = references.stream().filter(AiVideoTaskAsset::isPrimaryReference).count();
        if (primaryCount != 1) {
            throw new IllegalStateException("每组必须指定且只能指定一张主参考图");
        }
        if (references.stream().anyMatch(reference -> reference.getReferenceRole() == null || reference.getReferenceRole().isBlank())) {
            throw new IllegalStateException("请先为每张参考图指定用途");
        }
    }

    private void validateReplacementMappings(String itemId) {
        List<AiVideoTaskAsset> references = assetMapper.selectList(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getItemId, itemId)
                .eq(AiVideoTaskAsset::getAssetType, "VIDEO_REFERENCE_IMAGE")
                .orderByAsc(AiVideoTaskAsset::getShotSequence));
        if (references.stream().anyMatch(reference -> reference.getReferenceInstruction() == null
                || reference.getReferenceInstruction().isBlank())) {
            throw new IllegalStateException("请为每张对象图填写要替换原视频中的哪个对象");
        }
    }

    private String directReferencePrompt(AiVideoTask task, AiVideoTaskItem item) {
        List<AiVideoTaskAsset> references = assetMapper.selectList(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getItemId, item.getId())
                .eq(AiVideoTaskAsset::getAssetType, "VIDEO_REFERENCE_IMAGE")
                .orderByAsc(AiVideoTaskAsset::getShotSequence));
        if (AiVideoTaskService.isScriptVideoReplacement(task)) {
            StringBuilder prompt = new StringBuilder("【替换映射】\n");
            for (AiVideoTaskAsset reference : references) {
                int order = reference.getShotSequence() == null
                        ? references.indexOf(reference) + 1 : reference.getShotSequence();
                String instruction = reference.getReferenceInstruction() == null ? ""
                        : reference.getReferenceInstruction().replaceAll("\\s+", " ").trim();
                prompt.append("图").append(order)
                        .append(reference.isPrimaryReference() ? "（主参考）：" : "：")
                        .append(instruction).append('\n');
            }
            prompt.append("每张图只执行本行指定的替换，不得串用到其他对象。\n")
                    .append(item.getVideoPromptText().trim());
            return prompt.toString();
        }
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
        if (referenceLinks.isPubliclyReachable()) {
            return java.util.Optional.of(new Reference(referenceLinks.scriptSource(script.getId()), script.getSourceContentType()));
        }
        try {
            return java.util.Optional.of(data(script.getSourceFilePath(), script.getSourceContentType()));
        } catch (Exception exception) {
            throw new IllegalStateException("无法读取脚本原视频", exception);
        }
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
                .in(AiVideoGenerationJob::getStatus, List.of("QUEUED", "RUNNING", "REVIEWING"))
                .le(AiVideoGenerationJob::getNextPollAt, Instant.now())
                .orderByAsc(AiVideoGenerationJob::getNextPollAt));
        Instant now = Instant.now();
        jobs.forEach(job -> {
            if (pollingJobs.add(job.getId())) {
                if (jobMapper.claimPoll(job.getId(), now, now.plusSeconds(900)) == 1) {
                    executor.execute(() -> {
                        try { poll(job.getId()); }
                        finally { pollingJobs.remove(job.getId()); }
                    });
                } else pollingJobs.remove(job.getId());
            }
        });
    }

    private void poll(String jobId) {
        AiVideoGenerationJob job = jobMapper.selectById(jobId);
        if (job == null || !List.of("QUEUED", "RUNNING", "REVIEWING").contains(job.getStatus())) return;
        if ("REVIEWING".equals(job.getStatus())) { reviewSavedVideo(job); return; }
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
                AiVideoTaskAsset candidate = reviewAsset(job, "VIDEO_REVIEW_CANDIDATE");
                if (candidate == null) {
                    GeneratedVideo output = download(job, connection, providers.require(job.getProvider()), result.outputUrl());
                    candidate = new AiVideoTaskAsset(job.getOrganizationId(), job.getTaskId(), job.getItemId(),
                            "VIDEO_REVIEW_CANDIDATE", null, output.location(), "video/mp4", output.size(),
                            json(Map.of("jobId", job.getId())), job.getModelId());
                    assetMapper.insert(candidate);
                }
                Map<String, Object> usage = new LinkedHashMap<>(result.usage() == null ? Map.of() : result.usage());
                usage.put("reviewCandidateAssetId", candidate.getId());
                job.awaitingReview(result.outputUrl(), result.cost(), json(usage));
                jobMapper.updateById(job);
                reviewSavedVideo(job);
            } else if ("FAILED".equals(result.status())) {
                boolean qualityRetry = gridRejectionCount(job.getRunId(), job.getItemId()) > 0;
                String failure = result.errorMessage() == null || result.errorMessage().isBlank() ? "厂商生成失败" : result.errorMessage();
                job.failed("ACCEPTED", qualityRetry ? REVIEW_PAUSED + "首帧重做生成失败：" + failure : failure);
                jobMapper.updateById(job);
                if (qualityRetry) {
                    AiVideoTaskItem item = itemMapper.selectById(job.getItemId());
                    if (item != null) { item.videoFail(job.getErrorMessage()); itemMapper.updateById(item); }
                    refreshTask(job.getTaskId());
                } else submitNext(job.getRunId(), job.getTaskId(), job.getItemId());
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
            AiVideoGenerationJob persisted = jobMapper.selectById(job.getId());
            if (persisted != null && "REVIEWING".equals(persisted.getStatus())) return;
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

    private AiVideoTaskAsset reviewAsset(AiVideoGenerationJob job, String type) {
        return assetMapper.selectList(new LambdaQueryWrapper<AiVideoTaskAsset>()
                        .eq(AiVideoTaskAsset::getItemId, job.getItemId())
                        .eq(AiVideoTaskAsset::getOrganizationId, job.getOrganizationId())
                        .eq(AiVideoTaskAsset::getAssetType, type)).stream()
                .filter(asset -> job.getId().equals(readMap(asset.getPromptText()).get("jobId")))
                .findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String value) {
        try { return new LinkedHashMap<>(objectMapper.readValue(value, Map.class)); }
        catch (Exception ignored) { return new LinkedHashMap<>(); }
    }

    private void reviewSavedVideo(AiVideoGenerationJob job) {
        AiVideoTask task = taskMapper.selectById(job.getTaskId());
        AiVideoTaskItem item = itemMapper.selectById(job.getItemId());
        if (task == null || item == null) return;
        AiVideoTaskAsset candidate = reviewAsset(job, "VIDEO_REVIEW_CANDIDATE");
        Map<String, Object> usage = readMap(job.getUsageJson());
        try {
            if (candidate == null || firstFrameAudit == null || reviewFinalizer == null) {
                throw new IllegalStateException("视频审核依赖或已保存候选不可用");
            }
            VideoFirstFrameAuditService.AuditResult audit = firstFrameAudit.audit(task, item,
                    candidate.getFilePath(), job.getId());
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("layout", audit.layout());
            evidence.put("confidence", audit.confidence());
            evidence.put("reason", audit.reason());
            evidence.put("passed", audit.passed());
            evidence.put("confirmedRejection", audit.rejected());
            evidence.put("reviewedAt", Instant.now().toString());
            if (audit.framePath() != null && Files.isRegularFile(audit.framePath()) && audit.frameSizeBytes() > 0) {
                AiVideoTaskAsset frame = reviewAsset(job, "VIDEO_FIRST_FRAME");
                if (frame == null) {
                    String location = audit.framePath().toString();
                    long size = audit.frameSizeBytes();
                    if (ossStorage != null) {
                        try (InputStream input = Files.newInputStream(audit.framePath())) {
                            OssStorageService.StoredObject stored = ossStorage.store(input, size, size, "image/jpeg",
                                    "video-audits/" + job.getOrganizationId() + "/" + job.getTaskId() + "/" + job.getId(),
                                    "first-frame.jpg");
                            location = stored.location(); size = stored.size();
                        }
                    }
                    frame = new AiVideoTaskAsset(job.getOrganizationId(), job.getTaskId(), job.getItemId(),
                            "VIDEO_FIRST_FRAME", null, location, "image/jpeg", size,
                            json(Map.of("jobId", job.getId(), "audit", evidence)), job.getModelId());
                    assetMapper.insert(frame);
                }
                evidence.put("frameAssetId", frame.getId());
            }
            usage.put("firstFrameAudit", evidence);
            if (audit.passed()) {
                job.succeeded(job.getOutputUrl(), job.getCostAmount(), json(usage));
            } else {
                int rejections = gridRejectionCount(job.getRunId(), job.getItemId()) + 1;
                String message = audit.rejected()
                        ? GRID_REJECTED + "首帧出现九宫格、分屏或拼贴：" + audit.reason()
                            + (rejections <= MAX_GRID_REGENERATIONS
                                ? "；已安排加强提示词后自动重做（" + rejections + "/" + MAX_GRID_REGENERATIONS + "）"
                                : "；自动重做已达上限，视频和首帧已保留，请检查后手动处理")
                        : REVIEW_PAUSED + "首帧审核未能确认通过：" + audit.reason() + "；视频和已截首帧已保留，未再次付费生成";
                job.succeeded(job.getOutputUrl(), job.getCostAmount(), json(usage));
                job.failed("ACCEPTED", message);
            }
            if (reviewFinalizer.finish(job, item, candidate, audit.passed())) refreshTask(job.getTaskId());
        } catch (Exception exception) {
            usage.put("firstFrameAudit", Map.of("layout", "UNKNOWN", "passed", false,
                    "reason", "审核保存或处理失败", "reviewedAt", Instant.now().toString()));
            job.succeeded(job.getOutputUrl(), job.getCostAmount(), json(usage));
            job.failed("ACCEPTED", REVIEW_PAUSED + "首帧审核处理未完成，已保留视频候选，未再次付费生成");
            if (reviewFinalizer != null) {
                if (reviewFinalizer.finish(job, item, candidate, false)) refreshTask(job.getTaskId());
            }
        }
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
        Path output = directory.resolve("video-" + job.getId() + ".mp4");
        try {
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
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private synchronized void refreshTask(String taskId) {
        AiVideoTask task = taskMapper.selectById(taskId);
        if (task == null || !"VIDEO_GENERATING".equals(task.getStatus())) return;
        List<AiVideoTaskItem> items = itemMapper.selectList(new LambdaQueryWrapper<AiVideoTaskItem>().eq(AiVideoTaskItem::getTaskId, taskId));
        for (AiVideoTaskItem item : items) {
            if (!"VIDEO_READY".equals(item.getStatus()) && hasCurrentVideoResult(item.getId())) {
                item.videoComplete();
                itemMapper.updateById(item);
            }
        }
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
