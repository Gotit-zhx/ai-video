CREATE TABLE commerce_batch_reviews (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 organization_id VARCHAR(36) NOT NULL,
 batch_id VARCHAR(36) NOT NULL,
 template_snapshot_id VARCHAR(36) NOT NULL,
 evidence_json JSON NOT NULL,
 reviewed_by VARCHAR(36) NOT NULL,
 reviewed_at TIMESTAMP(6) NOT NULL,
 UNIQUE KEY uk_commerce_batch_review (organization_id,batch_id)
);
