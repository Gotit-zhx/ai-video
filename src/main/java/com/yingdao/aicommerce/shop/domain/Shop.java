package com.yingdao.aicommerce.shop.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.Instant;
import java.util.UUID;

@TableName("shops")
public class Shop {
    @TableId private String id;
    private String organizationId;
    private String platform;
    private String externalShopId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String merchantId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String shopCipher;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String sellerType;
    private String name;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String region;
    private String status;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String credentialsSecretRef;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String authorizedScopes;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private Instant lastSyncedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String lastAuthError;
    @Version private long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected Shop() {}

    public Shop(String organizationId, MarketplacePlatform platform, String externalShopId,
                String merchantId, String shopCipher, String sellerType, String name,
                String region, String authorizedScopes) {
        this.id = UUID.randomUUID().toString();
        this.organizationId = organizationId;
        this.platform = platform.name();
        this.externalShopId = externalShopId;
        this.merchantId = merchantId;
        this.shopCipher = shopCipher;
        this.sellerType = sellerType;
        this.name = name;
        this.region = region;
        this.status = "ACTIVE";
        this.authorizedScopes = authorizedScopes;
        this.lastSyncedAt = Instant.now();
        this.createdAt = this.lastSyncedAt;
        this.updatedAt = this.lastSyncedAt;
    }

    public void authorized(String merchantId, String shopCipher, String sellerType,
                           String name, String region, String authorizedScopes) {
        this.merchantId = merchantId;
        this.shopCipher = shopCipher;
        this.sellerType = sellerType;
        this.name = name;
        this.region = region;
        this.authorizedScopes = authorizedScopes;
        this.status = "ACTIVE";
        this.lastAuthError = null;
        this.lastSyncedAt = Instant.now();
        this.updatedAt = this.lastSyncedAt;
    }

    public void tokenExpired(String message) {
        this.status = "TOKEN_EXPIRED";
        this.lastAuthError = message;
        this.updatedAt = Instant.now();
    }

    public void disabled() {
        this.status = "DISABLED";
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getPlatform() { return platform; }
    public String getExternalShopId() { return externalShopId; }
    public String getMerchantId() { return merchantId; }
    public String getShopCipher() { return shopCipher; }
    public String getSellerType() { return sellerType; }
    public String getName() { return name; }
    public String getRegion() { return region; }
    public String getStatus() { return status; }
    public String getCredentialsSecretRef() { return credentialsSecretRef; }
    public String getAuthorizedScopes() { return authorizedScopes; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public String getLastAuthError() { return lastAuthError; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
