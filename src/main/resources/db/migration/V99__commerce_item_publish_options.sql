CREATE TABLE commerce_batch_item_publish_options (
 organization_id VARCHAR(36) NOT NULL,
 batch_id VARCHAR(36) NOT NULL,
 item_id VARCHAR(36) NOT NULL,
 request_json LONGTEXT NOT NULL,
 frozen BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY (organization_id,batch_id,item_id)
);
