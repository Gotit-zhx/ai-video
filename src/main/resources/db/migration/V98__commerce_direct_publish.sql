CREATE TABLE commerce_batch_publications (
 organization_id VARCHAR(36) NOT NULL,
 execution_id VARCHAR(36) NOT NULL,
 item_id VARCHAR(36) NOT NULL,
 variant_index INT NOT NULL,
 capture_id VARCHAR(36) NULL,
 workflow_id VARCHAR(36) NULL,
 manifest_json LONGTEXT NOT NULL,
 PRIMARY KEY (organization_id, execution_id, item_id, variant_index)
);
CREATE TABLE commerce_batch_publish_options (
 organization_id VARCHAR(36) NOT NULL,
 batch_id VARCHAR(36) NOT NULL,
 shop_id VARCHAR(36) NOT NULL,
 request_json LONGTEXT NOT NULL,
 PRIMARY KEY (organization_id,batch_id,shop_id)
);
