package com.yingdao.aicommerce.product.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@TableName("product_capture_skus")
public class ProductCaptureSku {
    @TableId private String id;
    private String organizationId;
    private String captureId;
    private String skuKey;
    private String externalSkuId;
    private String sellerSku;
    private String variationName;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private Integer stock;
    private String imageUrl;
    private String imageSource;
    private String attributesJson;
    private String sourceJson;
    private int sortOrder;
    private Instant createdAt;
    private Instant updatedAt;

    protected ProductCaptureSku() {}

    public ProductCaptureSku(String organizationId, String captureId, String skuKey,
                             String externalSkuId, String sellerSku, String variationName,
                             BigDecimal price, BigDecimal originalPrice, Integer stock,
                             String imageUrl, String imageSource, String attributesJson,
                             String sourceJson, int sortOrder) {
        this.id = UUID.randomUUID().toString();
        this.organizationId = organizationId;
        this.captureId = captureId;
        this.skuKey = skuKey;
        this.externalSkuId = externalSkuId;
        this.sellerSku = sellerSku;
        this.variationName = variationName;
        this.price = price;
        this.originalPrice = originalPrice;
        this.stock = stock;
        this.imageUrl = imageUrl;
        this.imageSource = imageSource;
        this.attributesJson = attributesJson;
        this.sourceJson = sourceJson;
        this.sortOrder = sortOrder;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getCaptureId() { return captureId; }
    public String getSkuKey() { return skuKey; }
    public String getExternalSkuId() { return externalSkuId; }
    public String getSellerSku() { return sellerSku; }
    public String getVariationName() { return variationName; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getOriginalPrice() { return originalPrice; }
    public Integer getStock() { return stock; }
    public String getImageUrl() { return imageUrl; }
    public String getImageSource() { return imageSource; }
    public String getAttributesJson() { return attributesJson; }
    public String getSourceJson() { return sourceJson; }
    public int getSortOrder() { return sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
