package com.yingdao.aicommerce.video.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.video.domain.AiVideoGenerationJob;
import com.yingdao.aicommerce.video.domain.AiVideoTask;
import com.yingdao.aicommerce.video.domain.AiVideoTaskAsset;
import com.yingdao.aicommerce.video.domain.AiVideoTaskItem;
import com.yingdao.aicommerce.video.mapper.AiVideoGenerationJobMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskAssetMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskItemMapper;
import com.yingdao.aicommerce.video.mapper.AiVideoTaskMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class AiVideoMonitorService {
    private static final Set<String> ACTIVE = Set.of("IMAGE_GENERATING", "VIDEO_GENERATING");
    private static final Set<String> COMPLETE = Set.of("IMAGE_READY", "VIDEO_READY");
    private static final Set<String> GENERATED_IMAGE_TYPES = Set.of("MODEL_IMAGE", "PRODUCT_SCENE_IMAGE", "STORYBOARD_GRID");

    private final AiVideoTaskMapper taskMapper;
    private final AiVideoTaskItemMapper itemMapper;
    private final AiVideoTaskAssetMapper assetMapper;
    private final AiVideoGenerationJobMapper jobMapper;

    public AiVideoMonitorService(AiVideoTaskMapper taskMapper, AiVideoTaskItemMapper itemMapper,
                                 AiVideoTaskAssetMapper assetMapper, AiVideoGenerationJobMapper jobMapper) {
        this.taskMapper = taskMapper;
        this.itemMapper = itemMapper;
        this.assetMapper = assetMapper;
        this.jobMapper = jobMapper;
    }

    public MonitorSnapshot snapshot(PlatformPrincipal principal) {
        List<AiVideoTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<AiVideoTask>()
                .eq(AiVideoTask::getOrganizationId, principal.organizationId())
                .eq(AiVideoTask::getCreatedBy, principal.userId())
                .orderByDesc(AiVideoTask::getUpdatedAt));
        if (tasks.isEmpty()) return new MonitorSnapshot(new MonitorStats(0, 0, 0, 0, 0, 0), List.of());

        List<String> taskIds = tasks.stream().map(AiVideoTask::getId).toList();
        List<AiVideoTaskItem> items = itemMapper.selectList(new LambdaQueryWrapper<AiVideoTaskItem>()
                .in(AiVideoTaskItem::getTaskId, taskIds));
        List<AiVideoTaskAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .in(AiVideoTaskAsset::getTaskId, taskIds));
        List<AiVideoGenerationJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<AiVideoGenerationJob>()
                .in(AiVideoGenerationJob::getTaskId, taskIds));

        List<TaskSummary> summaries = tasks.stream().map(task -> new TaskSummary(
                task.getId(), task.getName(), task.getScriptName(), task.getStatus(), task.getProgress(),
                task.getTotalItems(), task.getCompletedItems(), task.getGenerationMode(), task.getAspectRatio(),
                task.getResolution(), task.getModelId(), task.getErrorMessage(),
                countItems(items, task.getId()), countGeneratedImages(assets, task.getId()),
                countVideoAssets(assets, task.getId()), countJobs(jobs, task.getId()),
                task.getCreatedAt(), task.getUpdatedAt(), task.getCompletedAt())).toList();
        int active = (int) tasks.stream().filter(task -> ACTIVE.contains(task.getStatus())).count();
        int failed = (int) tasks.stream().filter(task -> "FAILED".equals(task.getStatus())).count();
        int complete = (int) tasks.stream().filter(task -> COMPLETE.contains(task.getStatus())).count();
        int imageRequests = (int) assets.stream().filter(asset -> GENERATED_IMAGE_TYPES.contains(asset.getAssetType())).count();
        return new MonitorSnapshot(new MonitorStats(tasks.size(), active, failed, complete, imageRequests, jobs.size()), summaries);
    }

    public TaskMonitor detail(String taskId, PlatformPrincipal principal) {
        AiVideoTask task = taskMapper.selectOne(new LambdaQueryWrapper<AiVideoTask>()
                .eq(AiVideoTask::getId, taskId)
                .eq(AiVideoTask::getOrganizationId, principal.organizationId())
                .eq(AiVideoTask::getCreatedBy, principal.userId()));
        if (task == null) throw new BusinessException(HttpStatus.NOT_FOUND, "AI 视频任务不存在");

        List<AiVideoTaskItem> items = itemMapper.selectList(new LambdaQueryWrapper<AiVideoTaskItem>()
                .eq(AiVideoTaskItem::getTaskId, taskId).orderByAsc(AiVideoTaskItem::getCreatedAt));
        List<AiVideoTaskAsset> assets = assetMapper.selectList(new LambdaQueryWrapper<AiVideoTaskAsset>()
                .eq(AiVideoTaskAsset::getTaskId, taskId).orderByAsc(AiVideoTaskAsset::getCreatedAt));
        List<AiVideoGenerationJob> jobs = jobMapper.selectList(new LambdaQueryWrapper<AiVideoGenerationJob>()
                .eq(AiVideoGenerationJob::getTaskId, taskId).orderByDesc(AiVideoGenerationJob::getCreatedAt));

        List<ItemMonitor> itemMonitors = items.stream().map(item -> new ItemMonitor(
                item.getId(), item.getName(), item.getSku(), item.getSourceFileName(), item.getStatus(), item.getProgress(),
                item.getVideoPromptText(), item.getErrorMessage(), item.getCreatedAt(), item.getUpdatedAt(), item.getCompletedAt(),
                assets.stream().filter(asset -> item.getId().equals(asset.getItemId())).map(AssetMonitor::from).toList(),
                jobs.stream().filter(job -> item.getId().equals(job.getItemId())).map(RequestMonitor::from).toList())).toList();

        return new TaskMonitor(task.getId(), task.getName(), task.getScriptName(), task.getScriptVersion(),
                task.getStatus(), task.getProgress(), task.getGenerationMode(), task.getBackgroundMode(),
                task.getTargetMarket(), task.getProductCategory(), task.getAspectRatio(), task.getResolution(),
                task.getModelId(), task.getErrorMessage(), task.getCreatedAt(), task.getUpdatedAt(), task.getCompletedAt(), itemMonitors);
    }

    private long countItems(List<AiVideoTaskItem> items, String taskId) {
        return items.stream().filter(item -> taskId.equals(item.getTaskId())).count();
    }

    private long countGeneratedImages(List<AiVideoTaskAsset> assets, String taskId) {
        return assets.stream().filter(asset -> taskId.equals(asset.getTaskId())
                && GENERATED_IMAGE_TYPES.contains(asset.getAssetType())).count();
    }

    private long countVideoAssets(List<AiVideoTaskAsset> assets, String taskId) {
        return assets.stream().filter(asset -> taskId.equals(asset.getTaskId())
                && "VIDEO_RESULT".equals(asset.getAssetType())).count();
    }

    private long countJobs(List<AiVideoGenerationJob> jobs, String taskId) {
        return jobs.stream().filter(job -> taskId.equals(job.getTaskId())).count();
    }

    public record MonitorStats(int totalTasks, int activeTasks, int failedTasks, int completedTasks,
                               int imageRequests, int videoRequests) {}

    public record MonitorSnapshot(MonitorStats stats, List<TaskSummary> tasks) {}

    public record TaskSummary(String id, String name, String scriptName, String status, int progress,
                              int totalItems, int completedItems, String generationMode, String aspectRatio,
                              String resolution, String modelId, String errorMessage, long itemCount,
                              long imageRequests, long videoResults, long videoRequests,
                              Instant createdAt, Instant updatedAt, Instant completedAt) {}

    public record TaskMonitor(String id, String name, String scriptName, int scriptVersion, String status,
                              int progress, String generationMode, String backgroundMode, String targetMarket,
                              String productCategory, String aspectRatio, String resolution, String modelId,
                              String errorMessage, Instant createdAt, Instant updatedAt, Instant completedAt,
                              List<ItemMonitor> items) {}

    public record ItemMonitor(String id, String name, String sku, String sourceFileName, String status, int progress,
                              String videoPrompt, String errorMessage, Instant createdAt, Instant updatedAt,
                              Instant completedAt, List<AssetMonitor> imageEvents, List<RequestMonitor> videoRequests) {}

    public record AssetMonitor(String id, String type, Integer shotSequence, String contentType, long sizeBytes,
                               String prompt, String modelId, Instant createdAt) {
        static AssetMonitor from(AiVideoTaskAsset asset) {
            return new AssetMonitor(asset.getId(), asset.getAssetType(), asset.getShotSequence(),
                    asset.getContentType(), asset.getSizeBytes(), asset.getPromptText(), asset.getModelId(), asset.getCreatedAt());
        }
    }

    public record RequestMonitor(String id, String runId, int attempt, String provider, String modelId,
                                 String providerTaskId, String status, String requestState, int durationSeconds,
                                 String aspectRatio, String resolution, BigDecimal cost, String usage,
                                 String errorMessage, Instant nextPollAt, Instant createdAt, Instant updatedAt,
                                 Instant completedAt) {
        static RequestMonitor from(AiVideoGenerationJob job) {
            return new RequestMonitor(job.getId(), job.getRunId(), job.getAttemptNo(), job.getProvider(), job.getModelId(),
                    job.getProviderTaskId(), job.getStatus(), job.getRequestState(), job.getDurationSeconds(),
                    job.getAspectRatio(), job.getResolution(), job.getCostAmount(), job.getUsageJson(),
                    job.getErrorMessage(), job.getNextPollAt(), job.getCreatedAt(), job.getUpdatedAt(), job.getCompletedAt());
        }
    }
}
