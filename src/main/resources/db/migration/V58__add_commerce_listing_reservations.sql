CREATE TABLE commerce_shop_daily_usage (
 organization_id VARCHAR(36) NOT NULL,
 shop_id VARCHAR(36) NOT NULL,
 listing_date DATE NOT NULL,
 reserved_count INT NOT NULL DEFAULT 0,
 PRIMARY KEY (organization_id,shop_id,listing_date),
 CONSTRAINT chk_commerce_reserved_nonnegative CHECK (reserved_count >= 0)
);
CREATE TABLE commerce_listing_reservations (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 organization_id VARCHAR(36) NOT NULL,
 batch_id VARCHAR(36) NOT NULL,
 item_id VARCHAR(36) NOT NULL,
 shop_id VARCHAR(36) NOT NULL,
 listing_date DATE NOT NULL,
 time_zone VARCHAR(64) NOT NULL,
 created_by VARCHAR(36) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 UNIQUE KEY uk_commerce_reservation_item (organization_id,item_id),
 KEY idx_commerce_reservation_batch (organization_id,batch_id)
);
