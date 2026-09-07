CREATE TABLE commerce_design_attempts (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 organization_id VARCHAR(36) NOT NULL,
 design_id VARCHAR(36) NOT NULL,
 status VARCHAR(32) NOT NULL,
 reference_manifest_json JSON NOT NULL,
 result_json LONGTEXT NULL,
 error_code VARCHAR(80) NULL,
 created_at TIMESTAMP(6) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL,
 UNIQUE KEY uk_design_attempt (organization_id,design_id),
 KEY idx_design_attempt_state (status,updated_at)
);
