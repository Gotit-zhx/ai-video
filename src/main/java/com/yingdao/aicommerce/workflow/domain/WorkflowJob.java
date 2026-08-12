package com.yingdao.aicommerce.workflow.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@TableName(value = "workflow_jobs", autoResultMap = true)
public class WorkflowJob {

    @TableId
    private String id;

    private String jobNo;

    private String organizationId;

    private WorkflowJobType type;

    private WorkflowJobStatus status;

    private String title;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private Map<String, Object> inputPayload = new HashMap<>();

    private BigDecimal budgetLimit;

    private BigDecimal estimatedCost = BigDecimal.ZERO;

    private BigDecimal actualCost = BigDecimal.ZERO;

    private String errorMessage;

    private String createdBy;

    @Version
    private long version;

    private Instant createdAt;

    private Instant updatedAt;

    protected WorkflowJob() {
    }

    public WorkflowJob(
            String jobNo,
            String organizationId,
            WorkflowJobType type,
            String title,
            Map<String, Object> inputPayload,
            BigDecimal budgetLimit,
            String createdBy
    ) {
        this.id = UUID.randomUUID().toString();
        this.jobNo = jobNo;
        this.organizationId = organizationId;
        this.type = type;
        this.status = WorkflowJobStatus.DRAFT;
        this.title = title;
        this.inputPayload = inputPayload == null ? new HashMap<>() : new HashMap<>(inputPayload);
        this.budgetLimit = budgetLimit;
        this.createdBy = createdBy;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void transitionTo(WorkflowJobStatus targetStatus) {
        this.status = targetStatus;
        this.updatedAt = Instant.now();
        if (targetStatus != WorkflowJobStatus.FAILED) {
            this.errorMessage = null;
        }
    }

    public void fail(String message) {
        this.status = WorkflowJobStatus.FAILED;
        this.errorMessage = message;
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getJobNo() {
        return jobNo;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public WorkflowJobType getType() {
        return type;
    }

    public WorkflowJobStatus getStatus() {
        return status;
    }

    public String getTitle() {
        return title;
    }

    public Map<String, Object> getInputPayload() {
        return Map.copyOf(inputPayload);
    }

    public BigDecimal getBudgetLimit() {
        return budgetLimit;
    }

    public BigDecimal getEstimatedCost() {
        return estimatedCost;
    }

    public BigDecimal getActualCost() {
        return actualCost;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
