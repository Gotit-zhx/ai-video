package com.yingdao.aicommerce.video.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@TableName("ai_video_generation_jobs")
public class AiVideoGenerationJob {
    @TableId private String id;
    private String runId;
    private String organizationId;
    private String taskId;
    private String itemId;
    private int attemptNo;
    private String modelConfigId;
    private String provider;
    private String modelId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String providerTaskId;
    private String status;
    private String requestState;
    private int durationSeconds;
    private String aspectRatio;
    private String resolution;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String outputUrl;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private BigDecimal costAmount;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String usageJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String errorMessage;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String submitRequestJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String submitResponseJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String queryRequestJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String queryResponseJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private Instant nextPollAt;
    private Instant createdAt;
    private Instant updatedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private Instant completedAt;

    protected AiVideoGenerationJob() {}

    public AiVideoGenerationJob(String runId, String organizationId, String taskId, String itemId, int attemptNo,
                                String modelConfigId, String provider, String modelId,
                                int durationSeconds, String aspectRatio, String resolution) {
        this.id = UUID.randomUUID().toString();
        this.runId = runId;
        this.organizationId = organizationId;
        this.taskId = taskId;
        this.itemId = itemId;
        this.attemptNo = attemptNo;
        this.modelConfigId = modelConfigId;
        this.provider = provider;
        this.modelId = modelId;
        this.status = "SUBMITTING";
        this.requestState = "NOT_SENT";
        this.durationSeconds = durationSeconds;
        this.aspectRatio = aspectRatio;
        this.resolution = resolution;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void accepted(String providerTaskId, String status, Instant nextPollAt) {
        this.providerTaskId = providerTaskId;
        this.status = status;
        this.requestState = "ACCEPTED";
        this.nextPollAt = nextPollAt;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    public void captureSubmitExchange(String requestJson, String responseJson) {
        this.submitRequestJson = requestJson;
        this.submitResponseJson = responseJson;
        this.updatedAt = Instant.now();
    }

    /**
     * Persist the network boundary before calling the provider. A job left in
     * NOT_SENT can then be recovered safely after an application restart,
     * while a SENDING job is treated as possibly accepted to avoid duplicates.
     */
    public void sending() {
        this.requestState = "SENDING";
        this.updatedAt = Instant.now();
    }

    public void captureQueryExchange(String requestJson, String responseJson) {
        this.queryRequestJson = requestJson;
        this.queryResponseJson = responseJson;
        this.updatedAt = Instant.now();
    }

    public void running(String status, Instant nextPollAt) {
        this.status = status;
        this.nextPollAt = nextPollAt;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    public void retryDownload(String message, Instant nextPollAt) {
        this.status = "RUNNING";
        this.errorMessage = message;
        this.nextPollAt = nextPollAt;
        this.updatedAt = Instant.now();
    }

    public void succeeded(String outputUrl, BigDecimal costAmount, String usageJson) {
        this.status = "SUCCEEDED";
        this.outputUrl = outputUrl;
        this.costAmount = costAmount;
        this.usageJson = usageJson;
        this.nextPollAt = null;
        this.errorMessage = null;
        this.completedAt = Instant.now();
        this.updatedAt = this.completedAt;
    }

    public void failed(String requestState, String message) {
        this.status = "FAILED";
        this.requestState = requestState;
        this.errorMessage = message;
        this.nextPollAt = null;
        this.completedAt = Instant.now();
        this.updatedAt = this.completedAt;
    }

    public void unknown(String message) {
        this.status = "REVIEW_REQUIRED";
        this.requestState = "ACCEPTED_OR_UNKNOWN";
        this.errorMessage = message;
        this.nextPollAt = null;
        this.completedAt = Instant.now();
        this.updatedAt = this.completedAt;
    }

    public String getId() { return id; }
    public String getRunId() { return runId; }
    public String getOrganizationId() { return organizationId; }
    public String getTaskId() { return taskId; }
    public String getItemId() { return itemId; }
    public int getAttemptNo() { return attemptNo; }
    public String getModelConfigId() { return modelConfigId; }
    public String getProvider() { return provider; }
    public String getModelId() { return modelId; }
    public String getProviderTaskId() { return providerTaskId; }
    public String getStatus() { return status; }
    public String getRequestState() { return requestState; }
    public int getDurationSeconds() { return durationSeconds; }
    public String getAspectRatio() { return aspectRatio; }
    public String getResolution() { return resolution; }
    public String getOutputUrl() { return outputUrl; }
    public BigDecimal getCostAmount() { return costAmount; }
    public String getUsageJson() { return usageJson; }
    public String getErrorMessage() { return errorMessage; }
    public String getSubmitRequestJson() { return submitRequestJson; }
    public String getSubmitResponseJson() { return submitResponseJson; }
    public String getQueryRequestJson() { return queryRequestJson; }
    public String getQueryResponseJson() { return queryResponseJson; }
    public Instant getNextPollAt() { return nextPollAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
