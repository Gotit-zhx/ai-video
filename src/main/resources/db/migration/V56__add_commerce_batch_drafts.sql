CREATE TABLE commerce_batches (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    template_snapshot_id VARCHAR(36) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_commerce_batch_request (organization_id,idempotency_key),
    KEY idx_commerce_batch_list (organization_id,created_at),
    CONSTRAINT fk_commerce_batch_template FOREIGN KEY (template_snapshot_id) REFERENCES commerce_template_versions(id)
);
CREATE TABLE commerce_batch_items (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    organization_id VARCHAR(36) NOT NULL,
    batch_id VARCHAR(36) NOT NULL,
    capture_id VARCHAR(36) NOT NULL,
    capture_version BIGINT NOT NULL,
    shop_id VARCHAR(36) NOT NULL,
    shop_name VARCHAR(255) NOT NULL,
    planned_day INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_commerce_batch_target (batch_id,capture_id,shop_id),
    KEY idx_commerce_batch_items (organization_id,batch_id),
    CONSTRAINT fk_commerce_batch_item FOREIGN KEY (batch_id) REFERENCES commerce_batches(id)
);
