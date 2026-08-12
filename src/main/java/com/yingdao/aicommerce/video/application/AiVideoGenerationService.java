package com.yingdao.aicommerce.video.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.saas.application.ModelConfigService.RuntimeModelConnection;
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
import com.yingdao.aicommerce.video.mapper.AiVideoGenerationJobMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskAssetMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
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
    private final AiVideoTaskMapper taskMapper;
    private final AiVideoTaskItemMapper itemMapper;
    private final AiVideoTaskAssetMapper assetMapper;
    private final AiVideoGenerationJobMapper jobMapper;
    private final ModelConfigService modelConfigs;
    private final VideoProviderRegistry providers;
    private final VideoReferenceLinkService referenceLinks;
    private final VideoReferenceDeliveryService referenceDelivery;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final Set<String> queuedSubmissions = ConcurrentHashMap.newKeySet();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public AiVideoGenerationService(AiVideoTaskMapper taskMapper, AiVideoTaskItemMapper itemMapper,
                                    AiVideoTaskAssetMapper assetMapper, AiVideoGenerationJobMapper jobMapper,
                                    ModelConfigService modelConfigs, VideoProviderRegistry providers,
                                    VideoReferenceLinkService referenceLinks, VideoReferenceDeliveryService referenceDelivery,
                                    ObjectMapper objectMapper,
                                    @Qualifier("aiVideoGenerationExecutor") Executor executor) {
        this.taskMapper = taskMapper;
        this.itemMapper = itemMapper;
        this.assetMapper = assetMapper;
        this.jobMapper = jobMapper;
        this.modelConfigs = modelConfigs;
        this.providers = providers;
        this.referenceLinks = referenceLinks;
        this.referenceDelivery = referenceDelivery;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @Transactional
    public AiVideoTask start(AiVideoTask task) {
        List<RuntimeModelConnection> routes = modelConfigs.videoConnections(task.getOrganizationId());
        if (routes.isEmpty()) throw new IllegalStateException("请先在模型接入中配置并启用视频模型");
        RuntimeModelConnection primary = routes.getFirst();
        if ("AUTO".equals(primary.provider())) throw new IllegalStateException("无法识别视频厂商，请编辑视频模型并选择厂商");
        List<AiVideoTaskItem> allItems = itemMapper.selectList(new LambdaQueryWrapper<AiVideoTaskItem>()
                .eq(AiVideoTaskItem::getTaskId, task.getId()).orderByAsc(AiVideoTaskItem::getCreatedAt));
        if (allItems.isEmpty()) throw new IllegalStateException("请先上传并生成商品图片");
        List<AiVideoTaskItem> items = allItems.stream().filter(item -> !hasVideoResult(item.getId())).toList();
        if (items.isEmpty()) throw new IllegalStateException("当前任务的全部视频已经生成，无需重复提交");
        for (AiVideoTaskItem item : items) {
            if (!hasRequiredImages(item.getId())) throw new IllegalStateException(item.getName() + " 的首帧或九宫格尚未生成");
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

    @Scheduled(initialDelay = 10_000, fixedDelay = 30_000)
    public void recoverUnsubmittedItems() {
        Instant cutoff = Instant.now().minusSeconds(120);
        List<AiVideoTaskItem> stuck = itemMapper.selectList(new LambdaQueryWrapper<AiVideoTaskItem>()
                .eq(AiVideoTaskItem::getStatus, "VIDEO_SUBMITTING")
                .le(AiVideoTaskItem::getUpdatedAt, cutoff)
                .orderByAsc(AiVideoTaskItem::getUpdatedAt)
                .last("LIMIT 20"));
        for (AiVideoTaskItem item : stuck) {
            AiVideoTask task = taskMapper.selectById(item.getTaskId());
            if (task == null || !"VIDEO_GENERATING".equals(task.getStatus()) || hasVideoResult(item.getId())) continue;
            long activeJobs = jobMapper.selectCount(new LambdaQueryWrapper<AiVideoGenerationJob>()
                    .eq(AiVideoGenerationJob::getItemId, item.getId())
                    .in(AiVideoGenerationJob::getStatus, List.of("QUEUED", "RUNNING", "UNKNOWN")));
            if (activeJobs == 0) {
                queueSubmission(UUID.randomUUID().toString(), task.getId(), item.getId());
            }
        }
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

    private void submitNext(String runId, String taskId, String itemId) {
        AiVideoTask task = taskMapper.selectById(taskId);
        AiVideoTaskItem item = itemMapper.selectById(itemId);
        if (task == null || item == null || !"VIDEO_GENERATING".equals(task.getStatus())) return;
        List<RuntimeModelConnection> routes = modelConfigs.videoConnections(task.getOrganizationId());
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
        int duration = duration(task.getScriptJson());
        String resolution = "WENGAO".equals(connection.provider()) ? "768P" : "720p";
        AiVideoGenerationJob job = new AiVideoGenerationJob(runId, task.getOrganizationId(), taskId, itemId, attempt,
                connection.id(), connection.provider(), connection.modelId(), duration, task.getAspectRatio(), resolution);
        jobMapper.insert(job);
        try {
            VideoGenerationProvider provider = providers.require(connection.provider());
            GenerationRequest request = new GenerationRequest(item.getVideoPromptText(), references(task, item, connection),
                    duration, task.getAspectRatio(), resolution, audioReference(task));
            SubmitResult submitted = provider.submit(connection, request);
            job.accepted(submitted.providerTaskId(), submitted.status(), Instant.now().plusSeconds(pollSeconds(connection.provider())));
            jobMapper.updateById(job);
            item.videoQueued();
            itemMapper.updateById(item);
        } catch (VideoProviderException exception) {
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

    public String latestFailureMessage(String itemId) {
        return latestFailureMessage(null, itemId);
    }

    public Instant currentRunSubmittedAt(String taskId) {
        AiVideoGenerationJob latest = jobMapper.selectOne(new LambdaQueryWrapper<AiVideoGenerationJob>()
                .eq(AiVideoGenerationJob::getTaskId, taskId)
                .orderByDesc(AiVideoGenerationJob::getCreatedAt)
                .last("LIMIT 1"));
        if (latest == null || latest.getRunId() == null) return null;
        AiVideoGenerationJob first = jobMapper.selectOne(new LambdaQueryWrapper<AiVideoGenerationJob>()
                .eq(AiVideoGenerationJob::getTaskId, taskId)
                .eq(AiVideoGenerationJob::getRunId, latest.getRunId())
                .orderByAsc(AiVideoGenerationJob::getCreatedAt)
                .last("LIMIT 1"));
        return first == null ? null : first.getCreatedAt();
    }

    public Map<String, String> currentRunProviderTaskIds(String taskId) {
        AiVideoGenerationJob latest = jobMapper.selectOne(new LambdaQueryWrapper<AiVideoGenerationJob>()
                .eq(AiVideoGenerationJob::getTaskId, taskId)
                .orderByDesc(AiVideoGenerationJob::getCreatedAt)
                .last("LIMIT 1"));
        if (latest == null || latest.getRunId() == null) return Map.of();
        List<AiVideoGenerationJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<AiVideoGenerationJob>()
                .eq(AiVideoGenerationJob::getTaskId, taskId)
                .eq(AiVideoGenerationJob::getRunId, latest.getRunId())
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
        String provider = switch (latest.getProvider()) {
            case "DAYAN" -> "大衍";
            case "WENGAO" -> "文高";
            default -> latest.getProvider() == null || latest.getProvider().isBlank() ? "视频厂商" : latest.getProvider();
        };
        return provider + "：" + latest.getErrorMessage().trim();
    }

    private List<Reference> references(AiVideoTask task, AiVideoTaskItem item, RuntimeModelConnection connection) throws Exception {
        List<AiVideoTaskAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getItemId, item.getId()));
        AiVideoTaskAsset first = requireAsset(assets, "STORYBOARD_FIRST_FRAME");
        AiVideoTaskAsset grid = requireAsset(assets, "STORYBOARD_GRID");
        if (!referenceLinks.isPubliclyReachable() && "DAYAN".equals(connection.provider())) {
            throw new VideoProviderException("大衍只接收公网素材地址；部署后请设置 APP_PUBLIC_BASE_URL 为平台 HTTPS 地址", true);
        }
        if (referenceLinks.isPubliclyReachable()) {
            referenceDelivery.prepare(first);
            referenceDelivery.prepare(grid);
            return List.of(new Reference(referenceLinks.deliveryAsset(first.getId()), "image/jpeg"),
                    new Reference(referenceLinks.deliveryAsset(grid.getId()), "image/jpeg"),
                    new Reference(referenceLinks.source(item.getId()), item.getSourceContentType()));
        }
        return List.of(data(first.getFilePath(), first.getContentType()), data(grid.getFilePath(), grid.getContentType()),
                data(item.getSourceFilePath(), item.getSourceContentType()));
    }

    private Reference data(String filePath, String mediaType) throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of(filePath));
        return new Reference("data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes), mediaType);
    }

    private String audioReference(AiVideoTask task) {
        if (!"UPLOADED".equals(task.getMusicMode()) || task.getMusicFilePath() == null) return null;
        Path path = Path.of(task.getMusicFilePath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalStateException("已上传的配乐文件不存在");
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
                .orderByAsc(AiVideoGenerationJob::getNextPollAt).last("LIMIT 20"));
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
            if ("SUCCEEDED".equals(result.status())) {
                providerCompleted = true;
                if (result.outputUrl() == null || result.outputUrl().isBlank()) {
                    job.running("RUNNING", Instant.now().plusSeconds(pollSeconds(job.getProvider())));
                    jobMapper.updateById(job);
                    return;
                }
                Path output = download(job, result.outputUrl());
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
                if (item != null) { item.videoRunning(); itemMapper.updateById(item); }
                refreshTask(job.getTaskId());
            }
        } catch (Exception exception) {
            String message = providerCompleted
                    ? "厂商已完成，视频回传待重试：" + readable(exception)
                    : "查询厂商任务待重试：" + readable(exception);
            job.retryDownload(message,
                    Instant.now().plusSeconds(pollSeconds(job.getProvider())));
            jobMapper.updateById(job);
        }
    }

    private Path download(AiVideoGenerationJob job, String url) throws Exception {
        AiVideoTaskItem item = itemMapper.selectById(job.getItemId());
        Path directory = Path.of(item.getSourceFilePath()).toAbsolutePath().normalize().getParent();
        Files.createDirectories(directory);
        Path temporary = directory.resolve("video-" + job.getId() + ".part");
        Path output = directory.resolve("video-result.mp4");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("下载厂商视频失败：HTTP " + response.statusCode());
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
        return output;
    }

    private void replaceVideoAsset(AiVideoGenerationJob job, Path output) throws Exception {
        List<AiVideoTaskAsset> previous = assetMapper.selectList(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getItemId, job.getItemId()).eq(AiVideoTaskAsset::getAssetType, "VIDEO_RESULT"));
        previous.forEach(asset -> assetMapper.deleteById(asset.getId()));
        assetMapper.insert(new AiVideoTaskAsset(job.getOrganizationId(), job.getTaskId(), job.getItemId(), "VIDEO_RESULT",
                1, output.toString(), "video/mp4", Files.size(output), null, job.getModelId()));
    }

    private synchronized void refreshTask(String taskId) {
        AiVideoTask task = taskMapper.selectById(taskId);
        if (task == null || !"VIDEO_GENERATING".equals(task.getStatus())) return;
        List<AiVideoTaskItem> items = itemMapper.selectList(new LambdaQueryWrapper<AiVideoTaskItem>().eq(AiVideoTaskItem::getTaskId, taskId));
        int succeeded = (int) items.stream().filter(item -> "VIDEO_READY".equals(item.getStatus())).count();
        List<AiVideoTaskItem> failed = items.stream().filter(item -> "VIDEO_FAILED".equals(item.getStatus())).toList();
        int terminal = succeeded + failed.size();
        if (terminal == items.size()) {
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
    private String json(Object value) { try { return objectMapper.writeValueAsString(value == null ? Map.of() : value); } catch (Exception ignored) { return "{}"; } }
    private String readable(Throwable value) { return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage(); }
}
