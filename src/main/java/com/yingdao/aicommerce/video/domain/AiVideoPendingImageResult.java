package com.yingdao.aicommerce.video.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("ai_video_pending_image_results")
public class AiVideoPendingImageResult {
    @TableId private String id;
    private String organizationId;
    private String taskId;
    private String itemId;
    private String assetType;
    private String promptHash;
    private String resultUrl;
    private String resultContentType;
    private String modelId;
    private String mode;
    private String revisedPrompt;
    private int downloadAttempts;
    private String lastError;
    private Instant createdAt;
    private Instant updatedAt;

    protected AiVideoPendingImageResult() {}

    public AiVideoPendingImageResult(String organizationId, String taskId, String itemId, String assetType,
                                     String promptHash, String resultUrl, String resultContentType,
                                     String modelId, String mode, String revisedPrompt) {
        this.id = UUID.randomUUID().toString();
        this.organizationId = organizationId;
        this.taskId = taskId;
        this.itemId = itemId;
        this.assetType = assetType;
        this.promptHash = promptHash;
        this.resultUrl = resultUrl;
        this.resultContentType = resultContentType;
        this.modelId = modelId;
        this.mode = mode;
        this.revisedPrompt = revisedPrompt;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void downloadFailed(String message) {
        this.downloadAttempts += 1;
        this.lastError = message == null ? "结果图片下载失败" : message.substring(0, Math.min(1800, message.length()));
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getTaskId() { return taskId; }
    public String getItemId() { return itemId; }
    public String getAssetType() { return assetType; }
    public String getPromptHash() { return promptHash; }
    public String getResultUrl() { return resultUrl; }
    public String getResultContentType() { return resultContentType; }
    public String getModelId() { return modelId; }
    public String getMode() { return mode; }
    public String getRevisedPrompt() { return revisedPrompt; }
    public int getDownloadAttempts() { return downloadAttempts; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
