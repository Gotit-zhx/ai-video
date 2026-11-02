CREATE TABLE finance_actual_order_costs (
 order_record_id CHAR(64) PRIMARY KEY,
 organization_id CHAR(36) NOT NULL, shop_id CHAR(36) NOT NULL, order_id VARCHAR(200) NOT NULL,
 amount_cny DECIMAL(24,6) NOT NULL,
 source_name VARCHAR(250) NOT NULL, source_hash CHAR(64) NOT NULL,
 evidence_hash CHAR(64) NOT NULL, data_json JSON NOT NULL,
 version INT NOT NULL DEFAULT 1,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_actual_cost_order(organization_id,shop_id,order_id),
 CONSTRAINT fk_actual_cost_order FOREIGN KEY(order_record_id) REFERENCES finance_records(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_return_cost_receipts (
 id CHAR(64) PRIMARY KEY, order_record_id CHAR(64) NOT NULL,
 organization_id CHAR(36) NOT NULL, shop_id CHAR(36) NOT NULL, order_id VARCHAR(200) NOT NULL,
 receipt_date DATE NOT NULL, native_currency VARCHAR(12) NOT NULL,
 amount_cny DECIMAL(24,6) NOT NULL,
 source_name VARCHAR(250) NOT NULL, source_hash CHAR(64) NOT NULL,
 evidence_hash CHAR(64) NOT NULL, data_json JSON NOT NULL,
 version INT NOT NULL DEFAULT 1,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX idx_return_cost_period(organization_id,receipt_date,shop_id),
 INDEX idx_return_cost_order(organization_id,shop_id,order_id),
 CONSTRAINT fk_return_cost_order FOREIGN KEY(order_record_id) REFERENCES finance_records(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE finance_order_costs ADD COLUMN cost_basis VARCHAR(32) NOT NULL DEFAULT 'MERCHANT_SKU_STANDARD';
