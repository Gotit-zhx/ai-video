package com.yingdao.aicommerce.video.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;

import java.time.Instant;
import java.util.UUID;

@TableName("ai_video_tasks")
public class AiVideoTask {
    @TableId private String id;
    private String organizationId;
    private String name;
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

    public void updateItemCount(int count) {
        this.totalItems = count;
        this.status = count > 0 ? "READY" : "DRAFT";
        this.updatedAt = Instant.now();
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
