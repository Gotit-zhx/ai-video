CREATE TABLE commerce_capture_source_archives (
 organization_id VARCHAR(36) NOT NULL,
 capture_id VARCHAR(36) NOT NULL,
 capture_version BIGINT NOT NULL,
 image_id VARCHAR(36) NOT NULL,
 storage_key VARCHAR(160) NOT NULL,
 content_sha256 VARCHAR(64) NOT NULL,
 provenance VARCHAR(24) NOT NULL,
 created_by VARCHAR(36) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY (organization_id,capture_id,capture_version,image_id)
);
