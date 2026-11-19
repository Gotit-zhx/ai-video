CREATE TABLE product_capture_template_images (
    organization_id CHAR(36) NOT NULL,
    id CHAR(36) NOT NULL,
    created_by CHAR(36) NOT NULL,
    storage_key VARCHAR(160) NOT NULL,
    file_name VARCHAR(180) NOT NULL,
    mime_type VARCHAR(40) NOT NULL,
    byte_length INT NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (organization_id, id),
    UNIQUE KEY uk_capture_template_image_key (organization_id, storage_key)
);
CREATE TABLE product_capture_templates (
    organization_id CHAR(36) NOT NULL,
    id CHAR(36) NOT NULL,
    name VARCHAR(50) NOT NULL,
    description VARCHAR(160) NOT NULL DEFAULT '',
    content_json JSON NOT NULL,
    version BIGINT NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    created_by CHAR(36) NOT NULL,
    updated_by CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (organization_id, id),
    INDEX idx_capture_template_updated (organization_id, updated_at, id)
);
