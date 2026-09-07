CREATE TABLE commerce_design_generation_consents (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 organization_id VARCHAR(36) NOT NULL,
 design_id VARCHAR(36) NOT NULL,
 capture_version BIGINT NOT NULL,
 image_ids_json JSON NOT NULL,
 model_connection_id VARCHAR(36) NOT NULL,
 model_id VARCHAR(255) NOT NULL,
 confirmed_by VARCHAR(36) NOT NULL,
 confirmed_at TIMESTAMP(6) NOT NULL,
 UNIQUE KEY uk_design_generation_consent(organization_id,design_id)
);
