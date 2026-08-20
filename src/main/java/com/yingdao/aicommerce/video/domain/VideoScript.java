package com.yingdao.aicommerce.video.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("video_scripts")
public class VideoScript {

    @TableId
    private String id;
    private String organizationId;
    private String name;
    private String sourceType;
    private String sourceFileName;
    private String sourceFilePath;
    private String sourceContentType;
    private Long sourceSizeBytes;
    private String targetMarket;
    private String requirements;
    private String status;
    private int progress;
    private String summary;
    private String visualTone;
    private String scriptJson;
    private String modelConfigId;
    private String modelId;
    private String errorMessage;
    private String createdBy;
    private int version;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;

    protected VideoScript() {
    }

    public VideoScript(
            String id,
            String organizationId,
            String name,
            String sourceType,
            String sourceFileName,
            String sourceFilePath,
            String sourceContentType,
            Long sourceSizeBytes,
            String targetMarket,
            String requirements,
            String createdBy
    ) {
        this.id = id;
        this.organizationId = organizationId;
        this.name = name;
        this.sourceType = sourceType;
        this.sourceFileName = sourceFileName;
        this.sourceFilePath = sourceFilePath;
        this.sourceContentType = sourceContentType;
        this.sourceSizeBytes = sourceSizeBytes;
        this.targetMarket = targetMarket;
        this.requirements = requirements;
        this.status = "QUEUED";
        this.progress = 5;
        this.createdBy = createdBy;
        this.version = 1;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void markExtracting() {
        this.status = "EXTRACTING_FRAMES";
        this.progress = 20;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    public void markAnalyzing(String modelConfigId, String modelId) {
        this.status = "AI_ANALYZING";
        this.progress = 55;
        this.modelConfigId = modelConfigId;
        this.modelId = modelId;
        this.updatedAt = Instant.now();
    }

    public boolean advanceAnalysisProgress(int nextProgress) {
        if (!"AI_ANALYZING".equals(this.status)) return false;
        int normalized = Math.max(55, Math.min(nextProgress, 95));
        if (normalized <= this.progress) return false;
        this.progress = normalized;
        this.updatedAt = Instant.now();
        return true;
    }

    public void complete(String summary, String visualTone, String scriptJson) {
        this.summary = summary;
        this.visualTone = visualTone;
        this.scriptJson = scriptJson;
        this.status = "READY";
        this.progress = 100;
        this.errorMessage = null;
        this.completedAt = Instant.now();
        this.updatedAt = this.completedAt;
    }

    public void saveManual(String summary, String visualTone, String scriptJson) {
        this.summary = summary;
        this.visualTone = visualTone;
        this.scriptJson = scriptJson;
        this.status = "DRAFT";
        this.progress = 100;
        this.updatedAt = Instant.now();
    }

    public void fail(String message) {
        this.status = "FAILED";
        this.progress = 100;
        this.errorMessage = message == null ? "视频脚本拆解失败" : message.substring(0, Math.min(message.length(), 1800));
        this.completedAt = Instant.now();
        this.updatedAt = this.completedAt;
    }

    public void prepareRetry() {
        this.status = "QUEUED";
        this.progress = 5;
        this.errorMessage = null;
        this.completedAt = null;
        this.updatedAt = Instant.now();
    }

    public void prepareReanalysis() {
        prepareRetry();
        this.version += 1;
    }

    public void saveEditedJson(String scriptJson) {
        this.scriptJson = scriptJson;
        this.version += 1;
        this.updatedAt = Instant.now();
    }

    public void saveEditedVisualTone(String visualTone, String scriptJson) {
        this.visualTone = visualTone;
        this.scriptJson = scriptJson;
        this.version += 1;
        this.updatedAt = Instant.now();
    }

    public void preserveAnalysisModel(String modelConfigId, String modelId) {
        this.modelConfigId = modelConfigId;
        this.modelId = modelId;
    }

    public String getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getName() { return name; }
    public String getSourceType() { return sourceType; }
    public String getSourceFileName() { return sourceFileName; }
    public String getSourceFilePath() { return sourceFilePath; }
    public String getSourceContentType() { return sourceContentType; }
    public Long getSourceSizeBytes() { return sourceSizeBytes; }
    public String getTargetMarket() { return targetMarket; }
    public String getRequirements() { return requirements; }
    public String getStatus() { return status; }
    public int getProgress() { return progress; }
    public String getSummary() { return summary; }
    public String getVisualTone() { return visualTone; }
    public String getScriptJson() { return scriptJson; }
    public String getModelConfigId() { return modelConfigId; }
    public String getModelId() { return modelId; }
    public String getErrorMessage() { return errorMessage; }
    public String getCreatedBy() { return createdBy; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
