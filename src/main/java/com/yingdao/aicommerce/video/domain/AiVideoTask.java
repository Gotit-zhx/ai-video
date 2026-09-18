package com.yingdao.aicommerce.video.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

@TableName("ai_video_tasks")
public class AiVideoTask {
    private static final ObjectMapper GENERATION_RULES_MAPPER = new ObjectMapper();
    @TableId private String id;
    private String organizationId;
    private String name;
    private String taskType;
    private int durationSeconds;
    private String scriptId;
    private int scriptVersion;
    private String scriptName;
    private String scriptJson;
    private String targetMarket;
    private String generationMode;
    private String backgroundMode;
    private String musicMode;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String musicFileName;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String musicFilePath;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String musicContentType;
    private long musicSizeBytes;
    private String productCategory;
    private String sceneType;
    private String interactionType;
    private boolean allowHand;
    private String aspectRatio;
    private String resolution;
    private boolean autoStartVideoAfterImages;
    private boolean useScriptReferenceVideo;
    private boolean directReferenceVideo;
    private String generationRulesJson;
    private String qualityRulesJson;
    private String status;
    private int progress;
    private int totalItems;
    private int completedItems;
    private String modelConfigId;
    private String modelId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;
    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;

    protected AiVideoTask() {}

    public AiVideoTask(String organizationId, String name, String scriptId, int scriptVersion,
                       String scriptName, String scriptJson, String targetMarket, String generationMode,
                       String backgroundMode, String productCategory, String sceneType, String interactionType, boolean allowHand,
                       String aspectRatio, String resolution, boolean useScriptReferenceVideo, boolean directReferenceVideo,
                       String generationRulesJson, String qualityRulesJson, String createdBy) {
        this.id = UUID.randomUUID().toString();
        this.organizationId = organizationId;
        this.name = name;
        this.taskType = "PRODUCT";
        this.durationSeconds = 12;
        this.scriptId = scriptId;
        this.scriptVersion = scriptVersion;
        this.scriptName = scriptName;
        this.scriptJson = scriptJson;
        this.targetMarket = targetMarket;
        this.generationMode = generationMode;
        this.backgroundMode = backgroundMode;
        this.musicMode = "AI_GENERATED";
        this.productCategory = productCategory;
        this.sceneType = sceneType;
        this.interactionType = interactionType;
        this.allowHand = allowHand;
        this.aspectRatio = aspectRatio;
        this.resolution = resolution;
        this.useScriptReferenceVideo = useScriptReferenceVideo;
        this.directReferenceVideo = directReferenceVideo;
        this.generationRulesJson = generationRulesJson;
        this.qualityRulesJson = qualityRulesJson;
        this.status = "DRAFT";
        this.progress = 0;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static AiVideoTask general(String organizationId, String name, String promptSnapshot,
                                      int durationSeconds, String aspectRatio, String resolution,
                                      String generationRulesJson, String qualityRulesJson, String createdBy) {
        AiVideoTask task = new AiVideoTask();
        task.id = UUID.randomUUID().toString();
        task.organizationId = organizationId;
        task.name = name;
        task.taskType = "GENERAL";
        task.durationSeconds = durationSeconds;
        task.scriptVersion = 0;
        task.scriptName = "通用生成";
        task.scriptJson = promptSnapshot;
        task.generationMode = "GENERAL";
        task.backgroundMode = "REFERENCE";
        task.musicMode = "NONE";
        task.productCategory = "通用";
        task.sceneType = "用户提示词";
        task.interactionType = "用户提示词";
        task.aspectRatio = aspectRatio;
        task.resolution = resolution;
        task.directReferenceVideo = true;
        task.generationRulesJson = generationRulesJson;
        task.qualityRulesJson = qualityRulesJson;
        task.status = "DRAFT";
        task.createdBy = createdBy;
        task.createdAt = Instant.now();
        task.updatedAt = task.createdAt;
        return task;
    }

    public void updateItemCount(int count) {
        this.totalItems = count;
        this.status = count > 0 ? "READY" : "DRAFT";
        this.updatedAt = Instant.now();
    }

    public void addPendingCopies(int count) {
        updateItemCount(count);
        this.progress = 0;
        this.errorMessage = null;
        this.completedAt = null;
        this.autoStartVideoAfterImages = false;
    }

    public void syncItemCountPreservingResult(int count) {
        this.totalItems = Math.max(0, count);
        this.completedItems = Math.min(this.completedItems, this.totalItems);
        if (this.totalItems == 0) {
            this.status = "DRAFT";
            this.progress = 0;
            this.errorMessage = null;
            this.completedAt = null;
        }
        this.updatedAt = Instant.now();
    }

    public void attachMusic(String fileName, String filePath, String contentType, long sizeBytes) {
        this.musicMode = "UPLOADED";
        this.musicFileName = fileName;
        this.musicFilePath = filePath;
        this.musicContentType = contentType;
        this.musicSizeBytes = sizeBytes;
        this.updatedAt = Instant.now();
    }

    public void refreshScriptSnapshot(VideoScript script) {
        if (script == null || script.getScriptJson() == null || script.getScriptJson().isBlank()
                || !this.scriptId.equals(script.getId())) return;
        this.scriptVersion = script.getVersion();
        this.scriptName = script.getName();
        this.scriptJson = script.getScriptJson();
        this.updatedAt = Instant.now();
    }

    public void updateGenerationRulesJson(String generationRulesJson) {
        this.generationRulesJson = generationRulesJson;
        this.updatedAt = Instant.now();
    }

    public void start(String modelConfigId, String modelId) {
        this.status = "IMAGE_GENERATING";
        this.progress = 1;
        this.completedItems = 0;
        this.modelConfigId = modelConfigId;
        this.modelId = modelId;
        this.errorMessage = null;
        this.completedAt = null;
        this.updatedAt = Instant.now();
    }

    public void requestAutoStartVideo() {
        this.autoStartVideoAfterImages = true;
        this.updatedAt = Instant.now();
    }

    public void consumeAutoStartVideo() {
        this.autoStartVideoAfterImages = false;
        this.updatedAt = Instant.now();
    }

    public void reportProgress(int progress, int completedItems) {
        this.progress = Math.max(this.progress, Math.min(progress, 99));
        this.completedItems = completedItems;
        this.updatedAt = Instant.now();
    }

    public void complete(int completedItems, String warning) {
        this.status = "IMAGE_READY";
        this.progress = 100;
        this.completedItems = completedItems;
        this.errorMessage = warning;
        this.completedAt = Instant.now();
        this.updatedAt = this.completedAt;
    }

    public void fail(String message) {
        this.status = "FAILED";
        this.progress = 100;
        this.errorMessage = message;
        this.completedAt = Instant.now();
        this.updatedAt = this.completedAt;
    }

    public void startVideos(String modelConfigId, String modelId) {
        this.status = "VIDEO_GENERATING";
        this.progress = 1;
        this.completedItems = 0;
        this.modelConfigId = modelConfigId;
        this.modelId = modelId;
        this.errorMessage = null;
        this.completedAt = null;
        this.updatedAt = Instant.now();
    }

    public void completeVideos(int completedItems, String warning) {
        this.status = "VIDEO_READY";
        this.progress = 100;
        this.completedItems = completedItems;
        this.errorMessage = warning;
        this.completedAt = Instant.now();
        this.updatedAt = this.completedAt;
    }

    public String getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getName() { return name; }
    public String getTaskType() { return taskType == null || taskType.isBlank() ? "PRODUCT" : taskType; }
    public int getDurationSeconds() { return durationSeconds > 0 ? durationSeconds : 12; }
    public boolean isGeneral() { return "GENERAL".equals(getTaskType()); }
    public String getScriptId() { return scriptId; }
    public int getScriptVersion() { return scriptVersion; }
    public String getScriptName() { return scriptName; }
    public String getScriptJson() { return scriptJson; }
    public String getTargetMarket() { return targetMarket; }
    public String getGenerationMode() { return generationMode; }
    public String getBackgroundMode() { return backgroundMode; }
    public String getMusicMode() { return musicMode; }
    public String getMusicFileName() { return musicFileName; }
    public String getMusicFilePath() { return musicFilePath; }
    public String getMusicContentType() { return musicContentType; }
    public long getMusicSizeBytes() { return musicSizeBytes; }
    public String getProductCategory() { return productCategory; }
    public String getSceneType() { return sceneType; }
    public String getInteractionType() { return interactionType; }
    public boolean isAllowHand() { return allowHand; }
    public String getAspectRatio() { return aspectRatio; }
    public String getResolution() { return resolution; }
    public boolean isAutoStartVideoAfterImages() { return autoStartVideoAfterImages; }
    public boolean isUseScriptReferenceVideo() { return useScriptReferenceVideo; }
    public boolean isDirectReferenceVideo() { return directReferenceVideo; }
    public String getGenerationRulesJson() { return generationRulesJson; }
    public String getVideoReferenceMode() {
        if (generationRulesJson == null || generationRulesJson.isBlank()) return "GRID";
        try {
            String mode = GENERATION_RULES_MAPPER.readTree(generationRulesJson).path("videoReferenceMode").asText();
            return "INDIVIDUAL_FRAMES".equals(mode) ? mode : "GRID";
        } catch (Exception ignored) {
            return "GRID";
        }
    }
    public String getVideoRoute() {
        return generationRulesJson != null && generationRulesJson.contains("\"videoRoute\":\"SPEED\"")
                ? "SPEED" : "QUALITY";
    }
    public String getQualityRulesJson() { return qualityRulesJson; }
    public String getStatus() { return status; }
    public int getProgress() { return progress; }
    public int getTotalItems() { return totalItems; }
    public int getCompletedItems() { return completedItems; }
    public String getModelConfigId() { return modelConfigId; }
    public String getModelId() { return modelId; }
    public String getErrorMessage() { return errorMessage; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
