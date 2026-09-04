package com.yingdao.aicommerce.product.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("product_image_processing_jobs")
public class ProductImageProcessingJob {
    @TableId private String id;
    private String organizationId;
    private String captureId;
    private String createdBy;
    private String idempotencyKey;
    private String provider;
    private String status;
    private int totalImages;
    private int processedImages;
    private String requestJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String responseJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private Instant completedAt;

    protected ProductImageProcessingJob() {}

    public ProductImageProcessingJob(String organizationId, String captureId, String createdBy,
                                     String idempotencyKey, String provider, int totalImages,
                                     String requestJson) {
        this.id = UUID.randomUUID().toString();
        this.organizationId = organizationId;
        this.captureId = captureId;
        this.createdBy = createdBy;
        this.idempotencyKey = idempotencyKey;
        this.provider = provider;
        this.status = "PENDING";
        this.totalImages = totalImages;
        this.requestJson = requestJson;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void start() { this.status = "RUNNING"; this.updatedAt = Instant.now(); }
    public void incrementProcessed() { this.processedImages++; this.updatedAt = Instant.now(); }
    public void succeed(String responseJson) {
        this.status = "SUCCEEDED";
        this.responseJson = responseJson;
        this.errorMessage = null;
        this.completedAt = Instant.now();
        this.updatedAt = this.completedAt;
    }
    public void fail(String message, String responseJson) {
        this.status = "FAILED";
        this.errorMessage = message;
        this.responseJson = responseJson;
        this.completedAt = Instant.now();
        this.updatedAt = this.completedAt;
    }

    public String getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getCaptureId() { return captureId; }
    public String getCreatedBy() { return createdBy; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getProvider() { return provider; }
    public String getStatus() { return status; }
    public int getTotalImages() { return totalImages; }
    public int getProcessedImages() { return processedImages; }
    public String getRequestJson() { return requestJson; }
    public String getResponseJson() { return responseJson; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
