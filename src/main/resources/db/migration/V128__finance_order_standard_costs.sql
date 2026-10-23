CREATE TABLE finance_order_costs (
 order_record_id CHAR(64) PRIMARY KEY,
 organization_id CHAR(36) NOT NULL, shop_id CHAR(36) NOT NULL, order_id VARCHAR(200) NOT NULL,
 record_date DATE NOT NULL, status VARCHAR(24) NOT NULL,
 amount_cny DECIMAL(24,6) NULL, known_amount_cny DECIMAL(24,6) NOT NULL DEFAULT 0,
 line_count INT NOT NULL DEFAULT 0, matched_lines INT NOT NULL DEFAULT 0,
 fallback_lines INT NOT NULL DEFAULT 0, source_hash CHAR(64) NOT NULL,
 data_json JSON NOT NULL, version INT NOT NULL DEFAULT 1,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX idx_order_cost_range(organization_id,record_date,shop_id),
 INDEX idx_order_cost_order(organization_id,shop_id,order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE finance_order_cost_jobs (
 order_record_id CHAR(64) PRIMARY KEY,
 organization_id CHAR(36) NOT NULL, shop_id CHAR(36) NOT NULL,
 priority TINYINT NOT NULL DEFAULT 0,
 requested_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX idx_cost_job_time(priority,requested_at,order_record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE finance_cost_rebuilds (
 organization_id CHAR(36) NOT NULL, shop_id CHAR(36) NOT NULL,
 cursor_id CHAR(64) NOT NULL DEFAULT '', status VARCHAR(16) NOT NULL DEFAULT 'READY',
 requested_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(organization_id,shop_id),
 INDEX idx_cost_rebuild_status(status,requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
ALTER TABLE finance_records
 ADD INDEX idx_finance_cost_backfill(organization_id,shop_id,kind,id),
 ADD INDEX idx_finance_cost_order(organization_id,shop_id,kind,order_id),
 ADD INDEX idx_finance_cost_statement(organization_id,shop_id,kind,statement_id,source,order_id),
 ALGORITHM=INPLACE, LOCK=NONE;
