ALTER TABLE agent_model_configs
    ADD COLUMN provider VARCHAR(40) NOT NULL DEFAULT 'AUTO' AFTER model_type;

CREATE INDEX idx_agent_model_configs_video_route
    ON agent_model_configs(organization_id, model_type, status, default_model, updated_at);

ALTER TABLE ai_video_tasks
    DROP INDEX uk_ai_video_tasks_active_owner,
    DROP COLUMN active_owner_key,
    ADD COLUMN active_owner_key VARCHAR(80)
        GENERATED ALWAYS AS (
            CASE WHEN status IN ('IMAGE_GENERATING', 'VIDEO_GENERATING')
                 THEN CONCAT(organization_id, ':', created_by)
                 ELSE NULL
            END
        ) STORED,
    ADD UNIQUE KEY uk_ai_video_tasks_active_owner (active_owner_key);

CREATE TABLE ai_video_generation_jobs (
    id                  VARCHAR(36)  NOT NULL,
    run_id              VARCHAR(36)  NOT NULL,
    organization_id     VARCHAR(36)  NOT NULL,
    task_id             VARCHAR(36)  NOT NULL,
    item_id             VARCHAR(36)  NOT NULL,
    attempt_no          INT          NOT NULL,
    model_config_id     VARCHAR(36)  NOT NULL,
    provider            VARCHAR(40)  NOT NULL,
    model_id            VARCHAR(160) NOT NULL,
    provider_task_id    VARCHAR(180) NULL,
    status              VARCHAR(32)  NOT NULL,
    request_state       VARCHAR(32)  NOT NULL,
    duration_seconds    INT          NOT NULL,
    aspect_ratio        VARCHAR(16)  NOT NULL,
    resolution          VARCHAR(24)  NOT NULL,
    output_url          TEXT         NULL,
    cost_amount         DECIMAL(14,4) NULL,
    usage_json          TEXT         NULL,
    error_message       TEXT         NULL,
    next_poll_at        DATETIME(6)  NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    completed_at        DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_video_generation_attempt (item_id, run_id, attempt_no),
    INDEX idx_ai_video_generation_run (item_id, run_id, attempt_no),
    INDEX idx_ai_video_generation_poll (status, next_poll_at),
    INDEX idx_ai_video_generation_task (organization_id, task_id, item_id),
    CONSTRAINT fk_ai_video_generation_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_ai_video_generation_task FOREIGN KEY (task_id) REFERENCES ai_video_tasks(id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_video_generation_item FOREIGN KEY (item_id) REFERENCES ai_video_task_items(id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_video_generation_model FOREIGN KEY (model_config_id) REFERENCES agent_model_configs(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
