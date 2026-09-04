package com.yingdao.aicommerce.auth;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("extension_access_tokens")
public class ExtensionAccessToken {
    @TableId private String id;
    private String organizationId;
    private String userId;
    private String tokenHash;
    private String scopesJson;
    private String deviceName;
    private String extensionId;
    private String extensionVersion;
    private Instant expiresAt;
    private Instant lastUsedAt;
    private Instant revokedAt;
    private Instant createdAt;

    protected ExtensionAccessToken() {}

    public ExtensionAccessToken(String organizationId, String userId, String tokenHash,
                                String scopesJson, String deviceName, String extensionId,
                                String extensionVersion, Instant expiresAt) {
        this.id = UUID.randomUUID().toString();
        this.organizationId = organizationId;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.scopesJson = scopesJson;
        this.deviceName = deviceName;
        this.extensionId = extensionId;
        this.extensionVersion = extensionVersion;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public void touch() { this.lastUsedAt = Instant.now(); }
    public void revoke() { this.revokedAt = Instant.now(); }

    public String getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public String getScopesJson() { return scopesJson; }
    public String getDeviceName() { return deviceName; }
    public String getExtensionId() { return extensionId; }
    public String getExtensionVersion() { return extensionVersion; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
