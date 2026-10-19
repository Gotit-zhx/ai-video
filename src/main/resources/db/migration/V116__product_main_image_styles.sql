CREATE TABLE product_main_image_templates (
    id CHAR(36) PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    created_by CHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    request_key VARCHAR(80) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_main_style_org (organization_id, archived, created_at),
    UNIQUE KEY uk_main_style_request (organization_id, request_key)
);
CREATE TABLE product_main_image_template_images (
    id CHAR(36) PRIMARY KEY,
    template_id CHAR(36) NOT NULL,
    organization_id CHAR(36) NOT NULL,
    sort_order INT NOT NULL,
    storage_key VARCHAR(160) NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    analysis_json JSON NULL,
    error_message VARCHAR(1000) NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_main_style_order (template_id, sort_order),
    INDEX idx_main_style_analysis (status, updated_at),
    FOREIGN KEY (template_id) REFERENCES product_main_image_templates(id)
);
CREATE TABLE product_main_image_jobs (
    id CHAR(36) PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    workflow_id CHAR(36) NOT NULL,
    capture_id CHAR(36) NOT NULL,
    sort_order INT NOT NULL,
    source_key VARCHAR(160) NOT NULL,
    template_fingerprint CHAR(64) NOT NULL,
    reference_key VARCHAR(160) NOT NULL,
    prompt TEXT NOT NULL,
    size VARCHAR(24) NOT NULL,
    model_config_id CHAR(36) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    recovery_count INT NOT NULL DEFAULT 0,
    history_json JSON NULL,
    status VARCHAR(24) NOT NULL,
    result_json MEDIUMTEXT NULL,
    storage_key VARCHAR(160) NULL,
    review_json JSON NULL,
    error_message VARCHAR(1000) NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_main_style_job (workflow_id, sort_order, template_fingerprint),
    INDEX idx_main_style_capture (organization_id, capture_id),
    FOREIGN KEY (workflow_id) REFERENCES product_publish_workflows(id) ON DELETE CASCADE
);
ALTER TABLE product_publish_workflows DROP CHECK ck_product_publish_workflows_step;
ALTER TABLE product_publish_workflows ADD CONSTRAINT ck_product_publish_workflows_step
    CHECK (current_step IN ('IMAGES', 'STYLE_IMAGES', 'APPROVE', 'PUBLISH', 'COMPLETED'));
