package com.yingdao.aicommerce.saas.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("roles")
public class Role {

    @TableId
    private String id;
    private String organizationId;
    private String code;
    private String name;
    private String description;
    private String dataScope;
    private boolean builtIn;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;

    protected Role() {
    }

    public Role(
            String organizationId,
            String code,
            String name,
            String description,
            String dataScope,
            boolean builtIn
    ) {
        this.id = UUID.randomUUID().toString();
        this.organizationId = organizationId;
        this.code = code;
        this.name = name;
        this.description = description;
        this.dataScope = dataScope;
        this.builtIn = builtIn;
        this.status = "ACTIVE";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void updateDefinition(
            String name,
            String description,
            String dataScope,
            String status
    ) {
        this.name = name;
        this.description = description;
        this.dataScope = dataScope;
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getDataScope() { return dataScope; }
    public boolean isBuiltIn() { return builtIn; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
