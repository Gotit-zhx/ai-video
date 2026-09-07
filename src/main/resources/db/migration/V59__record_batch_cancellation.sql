ALTER TABLE commerce_listing_reservations
 ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'RESERVED',
 ADD COLUMN released_by VARCHAR(36) NULL,
 ADD COLUMN released_at TIMESTAMP(6) NULL;
CREATE TABLE commerce_batch_events (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 organization_id VARCHAR(36) NOT NULL,
 batch_id VARCHAR(36) NOT NULL,
 event_type VARCHAR(32) NOT NULL,
 reason VARCHAR(500) NOT NULL,
 actor_id VARCHAR(36) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 KEY idx_commerce_batch_events (organization_id,batch_id,created_at)
);
