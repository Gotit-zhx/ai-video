CREATE TABLE commerce_mockup_derivatives (
 organization_id VARCHAR(36) NOT NULL,
 cache_key VARCHAR(64) NOT NULL,
 kind VARCHAR(32) NOT NULL,
 storage_key VARCHAR(160) NOT NULL,
 content_sha256 VARCHAR(64) NOT NULL,
 report_json JSON NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY (organization_id, cache_key)
);
