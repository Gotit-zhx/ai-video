package com.yingdao.aicommerce.saas.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("departments")
public class Department {

    @TableId
    private String id;
    private String organizationId;
    private String parentId;
    private String name;
    private String code;
    private String leaderUserId;
    private int sortOrder;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;

    protected Department() {
    }

    public Department(
            String organizationId,
            String parentId,
            String name,
            String code,
            int sortOrder
    ) {
        this.id = UUID.randomUUID().toString();
        this.organizationId = organizationId;
        this.parentId = parentId;
        this.name = name;
        this.code = code;
        this.sortOrder = sortOrder;
        this.status = "ACTIVE";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void updateDefinition(String parentId, String name, String code) {
        this.parentId = parentId;
        this.name = name;
        this.code = code;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getParentId() { return parentId; }
    public String getName() { return name; }
    public String getCode() { return code; }
    public String getLeaderUserId() { return leaderUserId; }
    public int getSortOrder() { return sortOrder; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
