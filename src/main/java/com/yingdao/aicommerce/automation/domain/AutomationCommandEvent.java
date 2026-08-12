package com.yingdao.aicommerce.automation.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("automation_command_events")
public class AutomationCommandEvent {

    @TableId
    private String id;
    private String commandId;
    private String organizationId;
    private String userId;
    private String eventType;
    private String source;
    private Integer progressPercent;
    private String message;
    private String payload;
    private Instant createdAt;

    protected AutomationCommandEvent() {
    }

    public AutomationCommandEvent(
            AutomationCommand command,
            String eventType,
            String source,
            Integer progressPercent,
            String message,
            String payload
    ) {
        this.id = UUID.randomUUID().toString();
        this.commandId = command.getId();
        this.organizationId = command.getOrganizationId();
        this.userId = command.getCreatedBy();
        this.eventType = eventType;
        this.source = source;
        this.progressPercent = progressPercent;
        this.message = message;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getCommandId() { return commandId; }
    public String getOrganizationId() { return organizationId; }
    public String getUserId() { return userId; }
    public String getEventType() { return eventType; }
    public String getSource() { return source; }
    public Integer getProgressPercent() { return progressPercent; }
    public String getMessage() { return message; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
}
