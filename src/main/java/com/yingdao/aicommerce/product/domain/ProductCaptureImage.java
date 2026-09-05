package com.yingdao.aicommerce.product.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("product_capture_images")
public class ProductCaptureImage {
    @TableId private String id;
    private String organizationId;
    private String captureId;
    private String originalUrl;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String processedUrl;
    private String status;
    private String roleName;
    private String altText;
    private int sortOrder;
    private String mimeType;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String errorMessage;
    private String sourceJson;
    private Instant createdAt;
    private Instant updatedAt;

    protected ProductCaptureImage() {}

    public ProductCaptureImage(String organizationId, String captureId, String originalUrl,
                               String roleName, String altText, int sortOrder,
                               String mimeType, String sourceJson) {
        this.id = UUID.randomUUID().toString();
        this.organizationId = organizationId;
        this.captureId = captureId;
        this.originalUrl = originalUrl;
        this.status = "PENDING";
        this.roleName = roleName;
        this.altText = altText;
        this.sortOrder = sortOrder;
        this.mimeType = mimeType;
        this.sourceJson = sourceJson;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void processing() {
        this.status = "PROCESSING";
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    public void processed(String processedUrl) {
        this.status = "PROCESSED";
        this.processedUrl = processedUrl;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    public void failed(String message) {
        this.status = "FAILED";
        this.errorMessage = message;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getCaptureId() { return captureId; }
    public String getOriginalUrl() { return originalUrl; }
    public String getProcessedUrl() { return processedUrl; }
    public String getStatus() { return status; }
    public String getRoleName() { return roleName; }
    public String getAltText() { return altText; }
    public int getSortOrder() { return sortOrder; }
    public String getMimeType() { return mimeType; }
    public String getErrorMessage() { return errorMessage; }
    public String getSourceJson() { return sourceJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
