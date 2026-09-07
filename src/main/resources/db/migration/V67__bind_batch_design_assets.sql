CREATE TABLE commerce_batch_design_assets (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 organization_id VARCHAR(36) NOT NULL,
 batch_id VARCHAR(36) NOT NULL,
 item_id VARCHAR(36) NOT NULL,
 image_position INT NOT NULL,
 design_id VARCHAR(36) NOT NULL,
 output_id VARCHAR(36) NOT NULL,
 content_sha256 CHAR(64) NOT NULL,
 review_id VARCHAR(36) NOT NULL,
 bound_by VARCHAR(36) NOT NULL,
 bound_at TIMESTAMP(6) NOT NULL,
 UNIQUE KEY uk_batch_asset_position (organization_id,item_id,image_position),
 UNIQUE KEY uk_batch_asset_output (organization_id,item_id,output_id),
 KEY idx_batch_assets (organization_id,batch_id),
 CONSTRAINT ck_batch_image_position CHECK (image_position BETWEEN 1 AND 9)
);
