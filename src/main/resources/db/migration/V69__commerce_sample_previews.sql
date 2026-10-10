CREATE TABLE commerce_sample_previews (
 id VARCHAR(36) PRIMARY KEY,
 organization_id VARCHAR(36) NOT NULL,
 created_by VARCHAR(36) NOT NULL,
 request_key VARCHAR(80) NOT NULL,
 product VARCHAR(24) NOT NULL,
 variant_index INT NOT NULL,
 status VARCHAR(32) NOT NULL,
 model_id VARCHAR(160) NOT NULL,
 prompt TEXT NOT NULL,
 result_json MEDIUMTEXT NULL,
 storage_key VARCHAR(160) NULL,
 created_at TIMESTAMP(6) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL,
 UNIQUE KEY uk_sample_request(organization_id,created_by,request_key),
 KEY idx_sample_owner(organization_id,created_by,created_at)
);
