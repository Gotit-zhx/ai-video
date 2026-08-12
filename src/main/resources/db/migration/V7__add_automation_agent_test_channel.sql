CREATE TABLE automation_agents (
    id CHAR(36) PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    os_name VARCHAR(80) NOT NULL,
    agent_version VARCHAR(40) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ONLINE',
    accepting_jobs BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_automation_agents_status CHECK (status IN ('ONLINE', 'OFFLINE', 'DISABLED')),
    CONSTRAINT fk_automation_agents_organization
        FOREIGN KEY (organization_id) REFERENCES organizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_automation_agents_available
    ON automation_agents(organization_id, status, accepting_jobs, last_seen_at);

CREATE TABLE automation_commands (
    id CHAR(36) PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    agent_id CHAR(36) NOT NULL,
    session_id VARCHAR(80) NOT NULL,
    command_type VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    input_payload JSON NOT NULL,
    output_payload JSON,
    error_message VARCHAR(2000),
    created_by CHAR(36),
    claimed_at DATETIME(6),
    completed_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_automation_commands_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT fk_automation_commands_organization
        FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_automation_commands_agent
        FOREIGN KEY (agent_id) REFERENCES automation_agents(id),
    CONSTRAINT fk_automation_commands_creator
        FOREIGN KEY (created_by) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_automation_commands_agent_queue
    ON automation_commands(agent_id, status, created_at);

CREATE INDEX idx_automation_commands_session
    ON automation_commands(organization_id, session_id, created_at);
