CREATE TABLE commerce_batch_artwork_repairs (
 id VARCHAR(36) PRIMARY KEY,
 organization_id VARCHAR(36) NOT NULL,
 execution_id VARCHAR(36) NOT NULL,
 job_id VARCHAR(36) NOT NULL,
 request_key VARCHAR(64) NOT NULL,
 storage_key VARCHAR(160) NOT NULL,
 content_sha256 VARCHAR(64) NOT NULL,
 policy_json JSON NOT NULL,
 report_json JSON NULL,
 created_at TIMESTAMP(6) NOT NULL,
 UNIQUE KEY uk_artwork_repair (job_id,request_key),
 KEY ix_artwork_repairs (organization_id,execution_id)
);
