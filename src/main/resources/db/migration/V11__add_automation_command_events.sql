CREATE TABLE automation_command_events (
    id CHAR(36) PRIMARY KEY,
    command_id CHAR(36) NOT NULL,
    organization_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    source VARCHAR(32) NOT NULL,
    progress_percent INT,
    message TEXT NOT NULL,
    payload JSON,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_automation_command_events_command
        FOREIGN KEY (command_id) REFERENCES automation_commands(id) ON DELETE CASCADE,
    CONSTRAINT fk_automation_command_events_organization
        FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_automation_command_events_user
        FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE,
    INDEX idx_automation_command_events_command_time (command_id, created_at),
    INDEX idx_automation_command_events_user_time (organization_id, user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
