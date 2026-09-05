package com.yingdao.aicommerce.shop.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@TableName("shop_authorization_sessions")
public class ShopAuthorizationSession {
    @TableId private String id;
    private String organizationId;
    private String createdBy;
    private String platform;
    private String stateHash;
    private String callbackUrl;
    private String returnPath;
    private String status;
    private Instant expiresAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private Instant consumedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;

    protected ShopAuthorizationSession() {}

    public ShopAuthorizationSession(String organizationId, String createdBy,
                                    MarketplacePlatform platform, String stateHash,
                                    String callbackUrl, String returnPath, Duration ttl) {
        this.id = UUID.randomUUID().toString();
        this.organizationId = organizationId;
        this.createdBy = createdBy;
        this.platform = platform.name();
        this.stateHash = stateHash;
        this.callbackUrl = callbackUrl;
        this.returnPath = returnPath;
        this.status = "PENDING";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.expiresAt = this.createdAt.plus(ttl);
    }

    public void succeeded() {
        this.status = "SUCCEEDED";
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    public void consumed(Instant now) {
        this.status = "PROCESSING";
        this.consumedAt = now;
        this.updatedAt = now;
    }

    public void failed(String message) {
        this.status = "FAILED";
        this.errorMessage = message;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getCreatedBy() { return createdBy; }
    public String getPlatform() { return platform; }
    public String getStateHash() { return stateHash; }
    public String getCallbackUrl() { return callbackUrl; }
    public String getReturnPath() { return returnPath; }
    public String getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
