package com.yingdao.aicommerce.workflow.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("workflow_job_events")
public class WorkflowJobEvent {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String workflowJobId;
    private String organizationId;
    private String userId;
    private String eventType;
    private WorkflowJobStatus jobStatus;
    private String message;
    private Instant createdAt;

    protected WorkflowJobEvent() {
    }

    public WorkflowJobEvent(
            String workflowJobId,
            String organizationId,
            String userId,
            String eventType,
            WorkflowJobStatus jobStatus,
            String message
    ) {
        this.workflowJobId = workflowJobId;
        this.organizationId = organizationId;
        this.userId = userId;
        this.eventType = eventType;
        this.jobStatus = jobStatus;
        this.message = message;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getWorkflowJobId() { return workflowJobId; }
    public String getOrganizationId() { return organizationId; }
    public String getUserId() { return userId; }
    public String getEventType() { return eventType; }
    public WorkflowJobStatus getJobStatus() { return jobStatus; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
}
