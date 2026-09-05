package com.yingdao.aicommerce.product.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("product_publish_workflows")
public class ProductPublishWorkflow {
    @TableId private String id;
    private String organizationId;
    private String captureId;
    private String createdBy;
    private String idempotencyKey;
    private String requestFingerprint;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String publicationTargetKey;
    private String destinationPlatform;
    private String shopId;
    private long captureVersion;
    private long expectedCaptureVersion;
    private String requestJson;
    private String status;
    private String currentStep;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String imageJobId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String publishAuditId;
    private boolean publicationAttempted;
    private int attemptCount;
    private int retryCount;
    private Instant nextAttemptAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String lockedBy;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private Instant lockedUntil;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private Instant completedAt;

    protected ProductPublishWorkflow() {}

    public ProductPublishWorkflow(String organizationId, String captureId, String createdBy,
                                  String idempotencyKey, String requestFingerprint,
                                  String publicationTargetKey, String destinationPlatform,
                                  String shopId, long captureVersion, String requestJson,
                                  String currentStep) {
        this.id = UUID.randomUUID().toString();
        this.organizationId = organizationId;
        this.captureId = captureId;
        this.createdBy = createdBy;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.publicationTargetKey = publicationTargetKey;
        this.destinationPlatform = destinationPlatform;
        this.shopId = shopId;
        this.captureVersion = captureVersion;
        this.expectedCaptureVersion = captureVersion;
        this.requestJson = requestJson;
        this.status = "PENDING";
        this.currentStep = currentStep;
        this.nextAttemptAt = Instant.now();
        this.createdAt = this.nextAttemptAt;
        this.updatedAt = this.nextAttemptAt;
    }

    public String getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getCaptureId() { return captureId; }
    public String getCreatedBy() { return createdBy; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public String getPublicationTargetKey() { return publicationTargetKey; }
    public String getDestinationPlatform() { return destinationPlatform; }
    public String getShopId() { return shopId; }
    public long getCaptureVersion() { return captureVersion; }
    public long getExpectedCaptureVersion() { return expectedCaptureVersion; }
    public String getRequestJson() { return requestJson; }
    public String getStatus() { return status; }
    public String getCurrentStep() { return currentStep; }
    public String getImageJobId() { return imageJobId; }
    public String getPublishAuditId() { return publishAuditId; }
    public boolean isPublicationAttempted() { return publicationAttempted; }
    public int getAttemptCount() { return attemptCount; }
    public int getRetryCount() { return retryCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLockedBy() { return lockedBy; }
    public Instant getLockedUntil() { return lockedUntil; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
