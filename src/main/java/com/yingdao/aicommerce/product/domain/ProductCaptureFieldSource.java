package com.yingdao.aicommerce.product.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@TableName("product_capture_field_sources")
public class ProductCaptureFieldSource {
    @TableId private String id;
    private String organizationId;
    private String captureId;
    private String fieldName;
    private String sourceType;
    private String sourcePath;
    private String sourceValue;
    private BigDecimal confidence;
    private Instant createdAt;

    protected ProductCaptureFieldSource() {}

    public ProductCaptureFieldSource(String organizationId, String captureId, String fieldName,
                                     String sourceType, String sourcePath, String sourceValue,
                                     BigDecimal confidence) {
        this.id = UUID.randomUUID().toString();
        this.organizationId = organizationId;
        this.captureId = captureId;
        this.fieldName = fieldName;
        this.sourceType = sourceType;
        this.sourcePath = sourcePath;
        this.sourceValue = sourceValue;
        this.confidence = confidence;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getCaptureId() { return captureId; }
    public String getFieldName() { return fieldName; }
    public String getSourceType() { return sourceType; }
    public String getSourcePath() { return sourcePath; }
    public String getSourceValue() { return sourceValue; }
    public BigDecimal getConfidence() { return confidence; }
    public Instant getCreatedAt() { return createdAt; }
}
