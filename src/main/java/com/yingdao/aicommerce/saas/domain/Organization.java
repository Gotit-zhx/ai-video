package com.yingdao.aicommerce.saas.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("organizations")
public class Organization {

    @TableId
    private String id;
    private String code;
    private String name;
    private String status;
    private String logoUrl;
    private String description;
    private String contactName;
    private String contactPhone;
    private String planCode;
    private Instant createdAt;
    private Instant updatedAt;

    protected Organization() {
    }

    public Organization(String code, String name, String description) {
        this.id = UUID.randomUUID().toString();
        this.code = code;
        this.name = name;
        this.status = "ACTIVE";
        this.description = description;
        this.planCode = "STARTER";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void updateProfile(
            String name,
            String logoUrl,
            String description,
            String contactName,
            String contactPhone
    ) {
        this.name = name;
        this.logoUrl = logoUrl;
        this.description = description;
        this.contactName = contactName;
        this.contactPhone = contactPhone;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public String getLogoUrl() { return logoUrl; }
    public String getDescription() { return description; }
    public String getContactName() { return contactName; }
    public String getContactPhone() { return contactPhone; }
    public String getPlanCode() { return planCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
