package com.yingdao.aicommerce.video.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;

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
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String referenceRole;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String referenceInstruction;
    private boolean primaryReference;
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

    public static AiVideoTaskAsset videoReference(String organizationId, String taskId, String itemId,
                                                  int order, String filePath, String contentType, long sizeBytes,
                                                  String role, String instruction, boolean primary) {
        AiVideoTaskAsset asset = new AiVideoTaskAsset(organizationId, taskId, itemId, "VIDEO_REFERENCE_IMAGE",
                order, filePath, contentType, sizeBytes, null, "source-reference");
        asset.referenceRole = role;
        asset.referenceInstruction = instruction;
        asset.primaryReference = primary;
        return asset;
    }

    public void updateReference(int order, String role, String instruction, boolean primary) {
        this.shotSequence = order;
        this.referenceRole = role;
        this.referenceInstruction = instruction;
        this.primaryReference = primary;
    }

    public void relocate(String filePath, long sizeBytes) {
        this.filePath = filePath;
        this.sizeBytes = sizeBytes;
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
    public String getReferenceRole() { return referenceRole; }
    public String getReferenceInstruction() { return referenceInstruction; }
    public boolean isPrimaryReference() { return primaryReference; }
    public Instant getCreatedAt() { return createdAt; }
}
