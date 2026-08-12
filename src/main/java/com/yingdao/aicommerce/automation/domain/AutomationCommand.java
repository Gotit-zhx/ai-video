package com.yingdao.aicommerce.automation.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("automation_commands")
public class AutomationCommand {

    @TableId
    private String id;
    private String organizationId;
    private String agentId;
    private String sessionId;
    private String commandType;
    private String automationCode;
    private String status;
    private int progressPercent;
    private String progressMessage;
    private String inputPayload;
    private String outputPayload;
    private String errorMessage;
    private String createdBy;
    private Instant claimedAt;
    private Instant completedAt;
    private Instant createdAt;
    private Instant updatedAt;

    protected AutomationCommand() {
    }

    public AutomationCommand(
            String organizationId,
            String agentId,
            String sessionId,
            String commandType,
            String automationCode,
            String inputPayload,
            String createdBy
    ) {
        this.id = UUID.randomUUID().toString();
        this.organizationId = organizationId;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.commandType = commandType;
        this.automationCode = automationCode;
        this.status = "QUEUED";
        this.progressPercent = 0;
        this.progressMessage = "等待本机助手领取";
        this.inputPayload = inputPayload;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void claim() {
        this.status = "RUNNING";
        this.progressPercent = Math.max(this.progressPercent, 1);
        this.progressMessage = "本机助手已开始执行";
        this.claimedAt = Instant.now();
        this.updatedAt = this.claimedAt;
    }

    public void progress(int percent, String message) {
        this.progressPercent = Math.max(0, Math.min(percent, 99));
        this.progressMessage = message;
        this.updatedAt = Instant.now();
    }

    public void requestControl(String action) {
        if ("PAUSE".equals(action)) {
            this.status = "PAUSE_REQUESTED";
            this.progressMessage = "正在请求暂停";
        } else if ("INTERRUPT".equals(action)) {
            this.status = "INTERRUPT_REQUESTED";
            this.progressMessage = "正在中断本机任务";
        } else if ("CANCEL".equals(action)) {
            this.status = "CANCEL_REQUESTED";
            this.progressMessage = "正在取消任务";
        } else {
            throw new IllegalArgumentException("未知控制动作");
        }
        this.updatedAt = Instant.now();
    }

    public void acknowledgeControl(String action) {
        if ("PAUSE".equals(action)) {
            this.status = "PAUSED";
            this.progressMessage = "任务已暂停，可从断点继续";
        } else if ("INTERRUPT".equals(action)) {
            this.status = "INTERRUPTED";
            this.progressMessage = "任务已中断，可从断点恢复";
        } else if ("CANCEL".equals(action)) {
            this.status = "CANCELLED";
            this.progressMessage = "任务已取消";
            this.completedAt = Instant.now();
        } else {
            throw new IllegalArgumentException("未知控制动作");
        }
        this.updatedAt = Instant.now();
    }

    public void resume() {
        this.status = "QUEUED";
        this.progressMessage = "等待本机助手从断点恢复";
        this.updatedAt = Instant.now();
    }

    public void complete(boolean success, String outputPayload, String errorMessage) {
        this.status = success ? "SUCCEEDED" : "FAILED";
        this.progressPercent = success ? 100 : this.progressPercent;
        this.progressMessage = success ? "执行完成" : "执行失败";
        this.outputPayload = outputPayload;
        this.errorMessage = errorMessage;
        this.completedAt = Instant.now();
        this.updatedAt = this.completedAt;
    }

    public String getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getAgentId() { return agentId; }
    public String getSessionId() { return sessionId; }
    public String getCommandType() { return commandType; }
    public String getAutomationCode() { return automationCode; }
    public String getStatus() { return status; }
    public int getProgressPercent() { return progressPercent; }
    public String getProgressMessage() { return progressMessage; }
    public String getInputPayload() { return inputPayload; }
    public String getOutputPayload() { return outputPayload; }
    public String getErrorMessage() { return errorMessage; }
    public String getCreatedBy() { return createdBy; }
    public Instant getClaimedAt() { return claimedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
