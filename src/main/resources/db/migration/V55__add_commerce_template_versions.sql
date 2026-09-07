CREATE TABLE commerce_template_versions (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL,
    template_id VARCHAR(36) NOT NULL,
    version INT NOT NULL,
    kind VARCHAR(32) NOT NULL,
    name VARCHAR(120) NOT NULL,
    config_json JSON NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_commerce_template_version (organization_id, template_id, version),
    KEY idx_commerce_template_list (organization_id, created_at)
);
