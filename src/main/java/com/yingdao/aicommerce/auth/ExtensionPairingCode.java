package com.yingdao.aicommerce.auth;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("extension_pairing_codes")
public class ExtensionPairingCode {
    @TableId private String id;
    private String organizationId;
    private String userId;
    private String codeHash;
    private String scopesJson;
    private Instant expiresAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private Instant consumedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private Instant revokedAt;
    private Instant createdAt;

    protected ExtensionPairingCode() {}

    public ExtensionPairingCode(String organizationId, String userId, String codeHash,
                                String scopesJson, Instant expiresAt) {
        this.id = UUID.randomUUID().toString();
        this.organizationId = organizationId;
        this.userId = userId;
        this.codeHash = codeHash;
        this.scopesJson = scopesJson;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public void consume() { this.consumedAt = Instant.now(); }
    public void revoke() { this.revokedAt = Instant.now(); }

    public String getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getUserId() { return userId; }
    public String getCodeHash() { return codeHash; }
    public String getScopesJson() { return scopesJson; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
