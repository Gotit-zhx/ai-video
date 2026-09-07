CREATE TABLE shop_operating_settings (
 shop_id VARCHAR(36) NOT NULL PRIMARY KEY,
 organization_id VARCHAR(36) NOT NULL,
 group_name VARCHAR(80) NOT NULL,
 environment VARCHAR(16) NOT NULL,
 daily_listing_limit INT NOT NULL,
 default_template_snapshot_id VARCHAR(36) NULL,
 version BIGINT NOT NULL,
 updated_by VARCHAR(36) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL,
 KEY idx_shop_operating_org (organization_id,group_name)
);
