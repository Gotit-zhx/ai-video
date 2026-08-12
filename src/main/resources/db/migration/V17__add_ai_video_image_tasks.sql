CREATE TABLE ai_video_tasks (
    id                  VARCHAR(36)  NOT NULL,
    organization_id     VARCHAR(36)  NOT NULL,
    name                VARCHAR(160) NOT NULL,
    script_id           VARCHAR(36)  NULL,
    script_version      INT          NOT NULL,
    script_name         VARCHAR(160) NOT NULL,
    script_json         LONGTEXT     NOT NULL,
    target_market       VARCHAR(80)  NULL,
    status              VARCHAR(32)  NOT NULL,
    progress            INT          NOT NULL DEFAULT 0,
    total_items         INT          NOT NULL DEFAULT 0,
    completed_items     INT          NOT NULL DEFAULT 0,
    model_config_id     VARCHAR(36)  NULL,
    model_id            VARCHAR(160) NULL,
    error_message       TEXT         NULL,
    created_by          VARCHAR(36)  NOT NULL,
    created_at          TIMESTAMP(6) NOT NULL,
    updated_at          TIMESTAMP(6) NOT NULL,
    completed_at        TIMESTAMP(6) NULL,
    active_owner_key VARCHAR(80)
        GENERATED ALWAYS AS (
            CASE WHEN status = 'IMAGE_GENERATING'
                 THEN CONCAT(organization_id, ':', created_by)
                 ELSE NULL
            END
        ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_video_tasks_active_owner (active_owner_key),
    INDEX idx_ai_video_tasks_owner_updated (organization_id, created_by, updated_at),
    CONSTRAINT fk_ai_video_tasks_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_ai_video_tasks_user FOREIGN KEY (created_by) REFERENCES app_users(id),
    CONSTRAINT fk_ai_video_tasks_script FOREIGN KEY (script_id) REFERENCES video_scripts(id) ON DELETE SET NULL
);

CREATE TABLE ai_video_task_items (
    id                  VARCHAR(36)  NOT NULL,
    organization_id     VARCHAR(36)  NOT NULL,
    task_id             VARCHAR(36)  NOT NULL,
    name                VARCHAR(160) NOT NULL,
    sku                 VARCHAR(80)  NOT NULL,
    source_file_name    VARCHAR(255) NOT NULL,
    source_file_path    VARCHAR(700) NOT NULL,
    source_content_type VARCHAR(120) NOT NULL,
    source_size_bytes   BIGINT       NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    progress            INT          NOT NULL DEFAULT 0,
    error_message       TEXT         NULL,
    created_at          TIMESTAMP(6) NOT NULL,
    updated_at          TIMESTAMP(6) NOT NULL,
    completed_at        TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_video_task_item_sku (task_id, sku),
    INDEX idx_ai_video_task_items_task (organization_id, task_id),
    CONSTRAINT fk_ai_video_task_items_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_ai_video_task_items_task FOREIGN KEY (task_id) REFERENCES ai_video_tasks(id) ON DELETE CASCADE
);

CREATE TABLE ai_video_task_assets (
    id                  VARCHAR(36)  NOT NULL,
    organization_id     VARCHAR(36)  NOT NULL,
    task_id             VARCHAR(36)  NOT NULL,
    item_id             VARCHAR(36)  NOT NULL,
    asset_type          VARCHAR(32)  NOT NULL,
    shot_sequence       INT          NULL,
    file_path           VARCHAR(700) NOT NULL,
    content_type        VARCHAR(120) NOT NULL,
    size_bytes          BIGINT       NOT NULL,
    prompt_text         TEXT         NULL,
    model_id            VARCHAR(160) NULL,
    created_at          TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_video_task_asset_slot (item_id, asset_type, shot_sequence),
    INDEX idx_ai_video_task_assets_task (organization_id, task_id, item_id),
    CONSTRAINT fk_ai_video_task_assets_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_ai_video_task_assets_task FOREIGN KEY (task_id) REFERENCES ai_video_tasks(id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_video_task_assets_item FOREIGN KEY (item_id) REFERENCES ai_video_task_items(id) ON DELETE CASCADE
);
