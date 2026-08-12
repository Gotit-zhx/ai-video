package com.yingdao.aicommerce.workflow.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("workflow_runs")
public class WorkflowRun {

    @TableId
    private String id;
    private String workflowJobId;
    private String organizationId;
    private String userId;
    private int attempt;
    private String status;
    private String workerId;
    private String streamRecordId;
    private String errorMessage;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant createdAt;
    private Instant updatedAt;

    protected WorkflowRun() {
    }

    public WorkflowRun(
            String workflowJobId,
            String organizationId,
            String userId,
            int attempt,
            String workerId,
            String streamRecordId
    ) {
        this.id = UUID.randomUUID().toString();
        this.workflowJobId = workflowJobId;
        this.organizationId = organizationId;
        this.userId = userId;
        this.attempt = attempt;
        this.status = "QUEUED";
        this.workerId = workerId;
        this.streamRecordId = streamRecordId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void running() {
        this.status = "RUNNING";
        this.startedAt = Instant.now();
        this.updatedAt = this.startedAt;
    }

    public void waitingForExecutor(String message) {
        this.status = "WAITING_EXECUTOR";
        this.errorMessage = message;
        this.finishedAt = Instant.now();
        this.updatedAt = this.finishedAt;
    }

    public void succeeded() {
        this.status = "SUCCEEDED";
        this.finishedAt = Instant.now();
        this.updatedAt = this.finishedAt;
        this.errorMessage = null;
    }

    public void failed(String message) {
        this.status = "FAILED";
        this.errorMessage = message;
        this.finishedAt = Instant.now();
        this.updatedAt = this.finishedAt;
    }

    public String getId() { return id; }
    public String getWorkflowJobId() { return workflowJobId; }
    public String getOrganizationId() { return organizationId; }
    public String getUserId() { return userId; }
    public int getAttempt() { return attempt; }
    public String getStatus() { return status; }
    public String getWorkerId() { return workerId; }
    public String getStreamRecordId() { return streamRecordId; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
