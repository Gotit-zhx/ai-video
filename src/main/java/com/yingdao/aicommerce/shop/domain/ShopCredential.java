package com.yingdao.aicommerce.shop.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.Instant;

@TableName("shop_credentials")
public class ShopCredential {
    @TableId private String shopId;
    private String organizationId;
    private String tokenGroupId;
    private String accessTokenCiphertext;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String refreshTokenCiphertext;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private Instant accessTokenExpiresAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private Instant refreshTokenExpiresAt;
    private Instant createdAt;
    private Instant updatedAt;
    @Version private long version;

    protected ShopCredential() {}

    public ShopCredential(String shopId, String organizationId, String accessTokenCiphertext,
                          String refreshTokenCiphertext, Instant accessTokenExpiresAt,
                          Instant refreshTokenExpiresAt) {
        this(shopId, organizationId, shopId, accessTokenCiphertext, refreshTokenCiphertext,
                accessTokenExpiresAt, refreshTokenExpiresAt);
    }

    public ShopCredential(String shopId, String organizationId, String tokenGroupId,
                          String accessTokenCiphertext, String refreshTokenCiphertext,
                          Instant accessTokenExpiresAt, Instant refreshTokenExpiresAt) {
        this.shopId = shopId;
        this.organizationId = organizationId;
        this.tokenGroupId = tokenGroupId;
        this.accessTokenCiphertext = accessTokenCiphertext;
        this.refreshTokenCiphertext = refreshTokenCiphertext;
        this.accessTokenExpiresAt = accessTokenExpiresAt;
        this.refreshTokenExpiresAt = refreshTokenExpiresAt;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void reauthorized(String tokenGroupId, String accessTokenCiphertext,
                             String refreshTokenCiphertext, Instant accessTokenExpiresAt,
                             Instant refreshTokenExpiresAt) {
        this.tokenGroupId = tokenGroupId;
        this.accessTokenCiphertext = accessTokenCiphertext;
        this.refreshTokenCiphertext = refreshTokenCiphertext;
        this.accessTokenExpiresAt = accessTokenExpiresAt;
        this.refreshTokenExpiresAt = refreshTokenExpiresAt;
        this.updatedAt = Instant.now();
    }

    public void rotate(String accessTokenCiphertext, String refreshTokenCiphertext,
                       Instant accessTokenExpiresAt, Instant refreshTokenExpiresAt) {
        this.accessTokenCiphertext = accessTokenCiphertext;
        if (refreshTokenCiphertext != null) this.refreshTokenCiphertext = refreshTokenCiphertext;
        this.accessTokenExpiresAt = accessTokenExpiresAt;
        if (refreshTokenExpiresAt != null) this.refreshTokenExpiresAt = refreshTokenExpiresAt;
        this.updatedAt = Instant.now();
    }

    public String getShopId() { return shopId; }
    public String getOrganizationId() { return organizationId; }
    public String getTokenGroupId() { return tokenGroupId; }
    public String getAccessTokenCiphertext() { return accessTokenCiphertext; }
    public String getRefreshTokenCiphertext() { return refreshTokenCiphertext; }
    public Instant getAccessTokenExpiresAt() { return accessTokenExpiresAt; }
    public Instant getRefreshTokenExpiresAt() { return refreshTokenExpiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
