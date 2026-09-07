CREATE TABLE commerce_design_drafts (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 organization_id VARCHAR(36) NOT NULL,
 capture_id VARCHAR(36) NOT NULL,
 capture_version BIGINT NOT NULL,
 request_key VARCHAR(128) NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 variant_index INT NOT NULL,
 mode VARCHAR(32) NOT NULL,
 brief VARCHAR(2000) NOT NULL,
 rights_evidence VARCHAR(2000) NOT NULL,
 source_images_json JSON NOT NULL,
 status VARCHAR(32) NOT NULL,
 created_by VARCHAR(36) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 UNIQUE KEY uk_design_request_variant(organization_id,request_key,variant_index),
 KEY idx_design_org_capture(organization_id,capture_id,created_at)
);
