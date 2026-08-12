package com.yingdao.aicommerce.video.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("ai_video_task_assets")
public class AiVideoTaskAsset {
    @TableId private String id;
    private String organizationId;
    private String taskId;
    private String itemId;
    private String assetType;
    private Integer shotSequence;
    private String filePath;
    private String contentType;
    private long sizeBytes;
    private String promptText;
    private String modelId;
    private Instant createdAt;

    protected AiVideoTaskAsset() {}

    public AiVideoTaskAsset(String organizationId, String taskId, String itemId, String assetType,
                            Integer shotSequence, String filePath, String contentType, long sizeBytes,
                            String promptText, String modelId) {
        this.id = UUID.randomUUID().toString();
        this.organizationId = organizationId;
        this.taskId = taskId;
        this.itemId = itemId;
        this.assetType = assetType;
        this.shotSequence = shotSequence;
        this.filePath = filePath;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.promptText = promptText;
        this.modelId = modelId;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getTaskId() { return taskId; }
    public String getItemId() { return itemId; }
    public String getAssetType() { return assetType; }
    public Integer getShotSequence() { return shotSequence; }
    public String getFilePath() { return filePath; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getPromptText() { return promptText; }
    public String getModelId() { return modelId; }
    public Instant getCreatedAt() { return createdAt; }
}
