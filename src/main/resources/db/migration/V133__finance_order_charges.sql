-- Independent internal expense records. Platform order and settlement ledgers remain unchanged.
CREATE TABLE finance_label_rules (
  id CHAR(36) PRIMARY KEY, organization_id CHAR(36) NOT NULL, group_id CHAR(36) NOT NULL,
  country VARCHAR(8) NOT NULL DEFAULT '', effective_date DATE NOT NULL,
  unit_price_cny DECIMAL(24,6) NOT NULL, version INT NOT NULL DEFAULT 1,
  created_by CHAR(36) NOT NULL, updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_label_rule (organization_id,group_id,country,effective_date)
);
CREATE TABLE finance_charge_imports (
  id CHAR(36) PRIMARY KEY, organization_id CHAR(36) NOT NULL, group_id CHAR(36) NOT NULL,
  source_name VARCHAR(255) NOT NULL, content_hash CHAR(64) NOT NULL, row_count INT NOT NULL,
  created_by CHAR(36) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_charge_import (organization_id,group_id,content_hash)
);
CREATE TABLE finance_charge_rows (
  id CHAR(36) PRIMARY KEY, organization_id CHAR(36) NOT NULL, group_id CHAR(36) NOT NULL,
  import_id CHAR(36) NOT NULL, source_row INT NOT NULL, order_id VARCHAR(255) NOT NULL,
  shop_hint VARCHAR(255) NOT NULL, principal_native DECIMAL(24,6) NOT NULL,
  service_fee_cny DECIMAL(24,6) NOT NULL, data_json JSON NOT NULL,
  KEY idx_charge_order (organization_id,group_id,order_id),
  KEY idx_charge_source (organization_id,import_id,source_row)
);
CREATE TABLE finance_marketing_orders (
  organization_id CHAR(36) NOT NULL, shop_id CHAR(36) NOT NULL, order_id VARCHAR(255) NOT NULL,
  service_fee_cny DECIMAL(24,6) NULL, source_count INT NOT NULL,
  PRIMARY KEY (organization_id,shop_id,order_id)
);
ALTER TABLE finance_charge_rows ADD COLUMN matched_shop_id CHAR(36) NULL,
 ADD INDEX idx_charge_match(organization_id,matched_shop_id,order_id);
ALTER TABLE finance_order_costs
 ADD COLUMN label_cost_cny DECIMAL(24,6) NOT NULL DEFAULT 0,
 ADD COLUMN charges_pending INT NOT NULL DEFAULT 0;
ALTER TABLE finance_records ADD INDEX idx_finance_marketing_order(organization_id,shop_id,order_id,kind), ALGORITHM=INPLACE, LOCK=NONE;
