package com.yingdao.aicommerce.saas.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("app_users")
public class PlatformUser {

    @TableId
    private String id;
    private String organizationId;
    private String departmentId;
    private String username;
    private String displayName;
    private String employeeNo;
    private String email;
    private String phone;
    private String jobTitle;
    private String passwordHash;
    private String status;
    private Instant lastLoginAt;
    private Instant createdAt;
    private Instant updatedAt;

    protected PlatformUser() {
    }

    public PlatformUser(
            String organizationId,
            String departmentId,
            String username,
            String displayName,
            String employeeNo,
            String email,
            String phone,
            String jobTitle,
            String passwordHash
    ) {
        this.id = UUID.randomUUID().toString();
        this.organizationId = organizationId;
        this.departmentId = departmentId;
        this.username = username;
        this.displayName = displayName;
        this.employeeNo = employeeNo;
        this.email = email;
        this.phone = phone;
        this.jobTitle = jobTitle;
        this.passwordHash = passwordHash;
        this.status = "ACTIVE";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void changeStatus(String status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public void updateProfile(
            String departmentId,
            String username,
            String displayName,
            String email,
            String phone,
            String jobTitle
    ) {
        this.departmentId = departmentId;
        this.username = username;
        this.displayName = displayName;
        this.employeeNo = null;
        this.email = email;
        this.phone = phone;
        this.jobTitle = jobTitle;
        this.updatedAt = Instant.now();
    }

    public void resetPassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getDepartmentId() { return departmentId; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getEmployeeNo() { return employeeNo; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getJobTitle() { return jobTitle; }
    public String getPasswordHash() { return passwordHash; }
    public String getStatus() { return status; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
