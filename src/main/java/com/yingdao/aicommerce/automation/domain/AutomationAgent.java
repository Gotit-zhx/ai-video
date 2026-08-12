package com.yingdao.aicommerce.automation.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("automation_agents")
public class AutomationAgent {

    @TableId
    private String id;
    private String organizationId;
    private String boundUserId;
    private String name;
    private String osName;
    private String agentVersion;
    private String status;
    private boolean acceptingJobs;
    private Instant lastSeenAt;
    private Instant createdAt;
    private Instant updatedAt;

    protected AutomationAgent() {
    }

    public AutomationAgent(
            String id,
            String organizationId,
            String name,
            String osName,
            String agentVersion,
            boolean acceptingJobs
    ) {
        this.id = id;
        this.organizationId = organizationId;
        this.name = name;
        this.osName = osName;
        this.agentVersion = agentVersion;
        this.status = "ONLINE";
        this.acceptingJobs = acceptingJobs;
        this.lastSeenAt = Instant.now();
        this.createdAt = this.lastSeenAt;
        this.updatedAt = this.lastSeenAt;
    }

    public void heartbeat(
            String name,
            String osName,
            String agentVersion,
            boolean acceptingJobs
    ) {
        this.name = name;
        this.osName = osName;
        this.agentVersion = agentVersion;
        this.status = "ONLINE";
        this.acceptingJobs = acceptingJobs;
        this.lastSeenAt = Instant.now();
        this.updatedAt = this.lastSeenAt;
    }

    public void bindToUser(String userId) {
        this.boundUserId = userId;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getOrganizationId() { return organizationId; }
    public String getBoundUserId() { return boundUserId; }
    public String getName() { return name; }
    public String getOsName() { return osName; }
    public String getAgentVersion() { return agentVersion; }
    public String getStatus() { return status; }
    public boolean isAcceptingJobs() { return acceptingJobs; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
