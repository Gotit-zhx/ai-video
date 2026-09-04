package com.yingdao.aicommerce.product.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("product_publish_audits")
public class ProductPublishAudit {
    @TableId private String id;
    private String organizationId;
    private String captureId;
    private String createdBy;
    private String idempotencyKey;
    private String destinationPlatform;
    private String shopId;
    private String provider;
    private String status;
    private boolean dryRun;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String externalId;
    private String requestJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String responseJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String errorMessage;
    private Instant createdAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private Instant completedAt;

    protected ProductPublishAudit() {}

    public ProductPublishAudit(String organizationId, String captureId, String createdBy,
                               String idempotencyKey, String destinationPlatform, String shopId,
                               String provider, boolean dryRun, String requestJson) {
        this.id = UUID.randomUUID().toString();
        this.organizationId = organizationId;
        this.captureId = captureId;
        this.createdBy = createdBy;
        this.idempotencyKey = idempotencyKey;
        this.destinationPlatform = destinationPlatform;
        this.shopId = shopId;
        this.provider = provider;
        this.status = "PENDING";
        this.dryRun = dryRun;
        this.requestJson = requestJson;
        this.createdAt = Instant.now();
    }

    public void publishing() { this.status = "PUBLISHING"; }
    public void completed(String externalId, String responseJson) {
        this.status = dryRun ? "DRY_RUN_SUCCEEDED" : "PUBLISHED";
        this.externalId = externalId;
        this.responseJson = responseJson;
        this.errorMessage = null;
        this.completedAt = Instant.now();
    }
    public void failed(String message, String responseJson) {
        this.status = "FAILED";
        this.errorMessage = message;
        this.responseJson = responseJson;
        this.completedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getCaptureId() { return captureId; }
    public String getCreatedBy() { return createdBy; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getDestinationPlatform() { return destinationPlatform; }
    public String getShopId() { return shopId; }
    public String getProvider() { return provider; }
    public String getStatus() { return status; }
    public boolean isDryRun() { return dryRun; }
    public String getExternalId() { return externalId; }
    public String getRequestJson() { return requestJson; }
    public String getResponseJson() { return responseJson; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
