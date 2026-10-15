package com.yingdao.aicommerce.product.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@TableName("product_captures")
public class ProductCapture {
    @TableId private String id;
    @com.baomidou.mybatisplus.annotation.TableLogic(value = "null", delval = "now()")
    private Instant deletedAt;
    private String organizationId;
    private String dataModule = "CAPTURE";
    private String createdBy;
    private String idempotencyKey;
    private String requestFingerprint;
    private String sourcePlatform;
    private String sourceUrl;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String sourceShopId;
    private String sourceProductId;
    private String status;
    private String title;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String description;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String categoryName;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String brandName;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String currency;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private BigDecimal priceMin;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private BigDecimal priceMax;
    private String normalizedData;
    private String rawData;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String approvedBy;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private Instant approvedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private Instant publishedAt;
    @Version private long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected ProductCapture() {}

    public ProductCapture(String organizationId, String createdBy, String idempotencyKey,
                          String requestFingerprint, String sourcePlatform, String sourceUrl,
                          String sourceShopId, String sourceProductId, String title,
                          String description, String categoryName, String brandName,
                          String currency, BigDecimal priceMin, BigDecimal priceMax,
                          String normalizedData, String rawData) {
        this.id = UUID.randomUUID().toString();
        this.organizationId = organizationId;
        this.createdBy = createdBy;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.sourcePlatform = sourcePlatform;
        this.sourceUrl = sourceUrl;
        this.sourceShopId = sourceShopId;
        this.sourceProductId = sourceProductId;
        this.status = "CAPTURED";
        this.title = title;
        this.description = description;
        this.categoryName = categoryName;
        this.brandName = brandName;
        this.currency = currency;
        this.priceMin = priceMin;
        this.priceMax = priceMax;
        this.normalizedData = normalizedData;
        this.rawData = rawData;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void updateDraft(String title, String description, String categoryName,
                            String brandName, String currency, BigDecimal priceMin,
                            BigDecimal priceMax, String normalizedData) {
        this.title = title;
        this.description = description;
        this.categoryName = categoryName;
        this.brandName = brandName;
        this.currency = currency;
        this.priceMin = priceMin;
        this.priceMax = priceMax;
        this.normalizedData = normalizedData;
        this.status = "CAPTURED";
        this.approvedBy = null;
        this.approvedAt = null;
        this.updatedAt = Instant.now();
    }

    public void recapture(String title, String description, String categoryName,
                          String brandName, String currency, BigDecimal priceMin,
                          BigDecimal priceMax, String normalizedData, String rawData) {
        updateDraft(title, description, categoryName, brandName, currency, priceMin, priceMax,
                normalizedData);
        this.rawData = rawData;
    }

    public void supplementCategory(String categoryName, String normalizedData) {
        this.categoryName = categoryName;
        this.normalizedData = normalizedData;
        this.updatedAt = Instant.now();
    }

    public void supplementCapturedDetails(String categoryName, String normalizedData, boolean newImages) {
        supplementCategory(categoryName, normalizedData);
        if (newImages && "READY".equals(this.status)) this.status = "CAPTURED";
    }

    public void startImageProcessing() {
        this.status = "IMAGE_PROCESSING";
        this.updatedAt = Instant.now();
    }

    public void imagesReady() {
        this.status = "READY";
        this.updatedAt = Instant.now();
    }

    public void fail(String ignoredMessage) {
        this.status = "FAILED";
        this.updatedAt = Instant.now();
    }

    public void approve(String userId) {
        this.status = "APPROVED";
        this.approvedBy = userId;
        this.approvedAt = Instant.now();
        this.updatedAt = this.approvedAt;
    }

    public void published() {
        this.status = "PUBLISHED";
        this.publishedAt = Instant.now();
        this.updatedAt = this.publishedAt;
    }

    public String getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getDataModule() { return dataModule; }
    public void assignModule(String module) {
        this.dataModule = com.yingdao.aicommerce.product.application.CommerceModule.validate(module);
    }
    public String getCreatedBy() { return createdBy; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public String getSourcePlatform() { return sourcePlatform; }
    public String getSourceUrl() { return sourceUrl; }
    public String getSourceShopId() { return sourceShopId; }
    public String getSourceProductId() { return sourceProductId; }
    public String getStatus() { return status; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getCategoryName() { return categoryName; }
    public String getBrandName() { return brandName; }
    public String getCurrency() { return currency; }
    public BigDecimal getPriceMin() { return priceMin; }
    public BigDecimal getPriceMax() { return priceMax; }
    public String getNormalizedData() { return normalizedData; }
    public String getRawData() { return rawData; }
    public String getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
