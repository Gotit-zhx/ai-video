package com.yingdao.aicommerce.task.domain;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("active_user_tasks")
public class ActiveUserTask {

    private String organizationId;
    private String userId;
    private String taskId;
    private String taskType;
    private String status;
    private Instant leaseExpiresAt;
    private Instant createdAt;
    private Instant updatedAt;

    protected ActiveUserTask() {
    }

    public ActiveUserTask(
            String organizationId,
            String userId,
            String taskId,
            String taskType,
            Instant leaseExpiresAt
    ) {
        this.organizationId = organizationId;
        this.userId = userId;
        this.taskId = taskId;
        this.taskType = taskType;
        this.status = "RUNNING";
        this.leaseExpiresAt = leaseExpiresAt;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void extend(Instant leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
        this.updatedAt = Instant.now();
    }

    public String getOrganizationId() { return organizationId; }
    public String getUserId() { return userId; }
    public String getTaskId() { return taskId; }
    public String getTaskType() { return taskType; }
    public String getStatus() { return status; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
