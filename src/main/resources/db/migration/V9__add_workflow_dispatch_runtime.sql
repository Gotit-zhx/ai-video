ALTER TABLE workflow_jobs
    DROP CHECK ck_workflow_jobs_status;

ALTER TABLE workflow_jobs
    ADD CONSTRAINT ck_workflow_jobs_status CHECK (
        status IN (
            'DRAFT', 'QUEUED', 'WAITING_EXECUTOR', 'RUNNING',
            'REVIEW_REQUIRED', 'APPROVED', 'COMPLETED', 'FAILED', 'CANCELLED'
        )
    );

CREATE TABLE workflow_runs (
    id CHAR(36) PRIMARY KEY,
    workflow_job_id CHAR(36) NOT NULL,
    organization_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    attempt INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    worker_id VARCHAR(120),
    stream_record_id VARCHAR(80),
    error_message VARCHAR(2000),
    started_at DATETIME(6),
    finished_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_workflow_runs_attempt UNIQUE (workflow_job_id, attempt),
    CONSTRAINT ck_workflow_runs_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'WAITING_EXECUTOR', 'SUCCEEDED', 'FAILED')
    ),
    CONSTRAINT fk_workflow_runs_job
        FOREIGN KEY (workflow_job_id) REFERENCES workflow_jobs(id) ON DELETE CASCADE,
    CONSTRAINT fk_workflow_runs_organization
        FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_workflow_runs_user
        FOREIGN KEY (user_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_workflow_runs_job_created
    ON workflow_runs(workflow_job_id, created_at);

CREATE INDEX idx_workflow_runs_status_updated
    ON workflow_runs(status, updated_at);

CREATE TABLE workflow_job_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    workflow_job_id CHAR(36) NOT NULL,
    organization_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    job_status VARCHAR(32) NOT NULL,
    message VARCHAR(500) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_workflow_job_events_job
        FOREIGN KEY (workflow_job_id) REFERENCES workflow_jobs(id) ON DELETE CASCADE,
    CONSTRAINT fk_workflow_job_events_organization
        FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_workflow_job_events_user
        FOREIGN KEY (user_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_workflow_job_events_job_id
    ON workflow_job_events(workflow_job_id, id);
