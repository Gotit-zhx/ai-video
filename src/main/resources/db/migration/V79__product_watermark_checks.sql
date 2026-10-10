CREATE TABLE product_watermark_checks (
 id VARCHAR(64) PRIMARY KEY,
 organization_id VARCHAR(36) NOT NULL,
 capture_id VARCHAR(36) NOT NULL,
 image_id VARCHAR(36) NOT NULL,
 source_sha256 CHAR(64) NOT NULL,
 source_key VARCHAR(140) NOT NULL,
 status VARCHAR(32) NOT NULL,
 detection_json MEDIUMTEXT,
 generation_json MEDIUMTEXT,
 candidate_key VARCHAR(140),
 output_key VARCHAR(140),
 validation_json MEDIUMTEXT,
 message VARCHAR(500),
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 INDEX idx_watermark_capture(organization_id,capture_id,image_id)
);
