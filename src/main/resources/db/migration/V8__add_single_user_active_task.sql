CREATE TABLE active_user_tasks (
    organization_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    task_id VARCHAR(80) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'RUNNING',
    lease_expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (organization_id, user_id),
    CONSTRAINT uk_active_user_tasks_task UNIQUE (task_id),
    CONSTRAINT ck_active_user_tasks_status
        CHECK (status IN ('QUEUED', 'RUNNING')),
    CONSTRAINT fk_active_user_tasks_organization
        FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_active_user_tasks_user
        FOREIGN KEY (user_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_active_user_tasks_lease
    ON active_user_tasks(lease_expires_at);
