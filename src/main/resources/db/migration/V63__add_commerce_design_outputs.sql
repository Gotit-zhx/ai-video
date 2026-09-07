CREATE TABLE commerce_design_outputs (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 organization_id VARCHAR(36) NOT NULL,
 design_id VARCHAR(36) NOT NULL,
 attempt_id VARCHAR(36) NOT NULL,
 storage_key VARCHAR(160) NOT NULL,
 content_sha256 CHAR(64) NOT NULL,
 mime_type VARCHAR(40) NOT NULL,
 byte_length INT NOT NULL,
 status VARCHAR(32) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 UNIQUE KEY uk_design_output_attempt(organization_id,attempt_id),
 KEY idx_design_output_org(organization_id,design_id)
);
