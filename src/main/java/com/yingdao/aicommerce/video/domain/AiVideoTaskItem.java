package com.yingdao.aicommerce.video.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;

import java.time.Instant;
import java.util.UUID;

@TableName("ai_video_task_items")
public class AiVideoTaskItem {
    @TableId private String id;
    private String organizationId;
    private String taskId;
    private String name;
    private String sku;
    private String sourceFileName;
    private String sourceFilePath;
    private String sourceContentType;
    private long sourceSizeBytes;
    private String status;
    private int progress;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;
    private String videoPromptText;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;

    protected AiVideoTaskItem() {}

    public AiVideoTaskItem(String organizationId, String taskId, String name, String sku,
                           String sourceFileName, String sourceFilePath, String sourceContentType, long sourceSizeBytes) {
        this.id = UUID.randomUUID().toString();
        this.organizationId = organizationId;
        this.taskId = taskId;
        this.name = name;
        this.sku = sku;
        this.sourceFileName = sourceFileName;
        this.sourceFilePath = sourceFilePath;
        this.sourceContentType = sourceContentType;
        this.sourceSizeBytes = sourceSizeBytes;
        this.status = "READY";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void start() { status = "GENERATING_MODEL"; progress = 1; errorMessage = null; updatedAt = Instant.now(); }
    public void attachSource(String sourceFilePath) { this.sourceFilePath = sourceFilePath; this.updatedAt = Instant.now(); }
    public void modelReady() { status = "GENERATING_STORYBOARD"; progress = 12; updatedAt = Instant.now(); }
    public void shotReady(int sequence) { status = "GENERATING_STORYBOARD"; progress = 10 + sequence * 10; updatedAt = Instant.now(); }
    public void complete() { status = "IMAGE_READY"; progress = 100; errorMessage = null; completedAt = Instant.now(); updatedAt = completedAt; }
    public void fail(String message) { status = "FAILED"; progress = 100; errorMessage = message; completedAt = Instant.now(); updatedAt = completedAt; }
    public void updateVideoPrompt(String videoPromptText) { this.videoPromptText = videoPromptText; this.updatedAt = Instant.now(); }
    public void startVideo() { status = "VIDEO_SUBMITTING"; progress = 1; errorMessage = null; completedAt = null; updatedAt = Instant.now(); }
    public void videoQueued() { status = "VIDEO_GENERATING"; progress = Math.max(progress, 8); errorMessage = null; updatedAt = Instant.now(); }
    public void videoRunning() { status = "VIDEO_GENERATING"; progress = Math.min(95, Math.max(progress + 4, 12)); updatedAt = Instant.now(); }
    public void videoComplete() { status = "VIDEO_READY"; progress = 100; errorMessage = null; completedAt = Instant.now(); updatedAt = completedAt; }
    public void videoFail(String message) { status = "VIDEO_FAILED"; progress = 100; errorMessage = message; completedAt = Instant.now(); updatedAt = completedAt; }
    public void updateVideoFailureMessage(String message) {
        if (!"VIDEO_FAILED".equals(status)) return;
        errorMessage = message;
        updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getTaskId() { return taskId; }
    public String getName() { return name; }
    public String getSku() { return sku; }
    public String getSourceFileName() { return sourceFileName; }
    public String getSourceFilePath() { return sourceFilePath; }
    public String getSourceContentType() { return sourceContentType; }
    public long getSourceSizeBytes() { return sourceSizeBytes; }
    public String getStatus() { return status; }
    public int getProgress() { return progress; }
    public String getErrorMessage() { return errorMessage; }
    public String getVideoPromptText() { return videoPromptText; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
