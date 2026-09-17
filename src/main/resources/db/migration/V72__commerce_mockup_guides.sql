CREATE TABLE commerce_mockup_guides (
 id VARCHAR(36) PRIMARY KEY,
 organization_id VARCHAR(36) NOT NULL,
 created_by VARCHAR(36) NOT NULL,
 request_key VARCHAR(80) NOT NULL,
 template_snapshot_id VARCHAR(36) NOT NULL,
 side VARCHAR(8) NOT NULL,
 bindings_json TEXT NOT NULL,
 base_hash VARCHAR(64) NOT NULL,
 storage_key VARCHAR(160) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 UNIQUE KEY uk_mockup_guide_request(organization_id,created_by,request_key),
 KEY idx_mockup_guide_owner(organization_id,created_by,created_at)
);
