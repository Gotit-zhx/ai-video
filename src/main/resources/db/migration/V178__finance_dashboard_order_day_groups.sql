CREATE TABLE finance_dashboard_order_groups LIKE finance_dashboard_order_values;

ALTER TABLE finance_dashboard_order_groups
 DROP PRIMARY KEY, DROP COLUMN order_id, DROP COLUMN member_id,
 ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 ADD COLUMN known_impressions BIGINT NOT NULL,
 ADD COLUMN known_clicks BIGINT NOT NULL,
 ADD COLUMN known_conversions BIGINT NOT NULL;

ALTER TABLE finance_dashboard_order_days
 ADD COLUMN groups_ready BOOLEAN NOT NULL DEFAULT FALSE,
 ALGORITHM=INSTANT;
