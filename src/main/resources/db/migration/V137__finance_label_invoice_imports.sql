-- Uploaded invoices replace configured per-order label prices. Old rules remain
-- historical data only and are no longer read by reporting or calculation.
CREATE TABLE finance_label_imports (
 id CHAR(36) PRIMARY KEY, organization_id CHAR(36) NOT NULL,
 source_name VARCHAR(250) NOT NULL, content_hash CHAR(64) NOT NULL,
 storage_name VARCHAR(100) NOT NULL, stored_bytes BIGINT NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
 customer VARCHAR(120) NOT NULL DEFAULT '', currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
 source_type VARCHAR(32) NOT NULL DEFAULT 'WAREHOUSE_INVOICE',
 period_start DATE NULL, period_end DATE NULL,
 row_count INT NOT NULL DEFAULT 0, matched_count INT NOT NULL DEFAULT 0,
 unmatched_count INT NOT NULL DEFAULT 0, review_count INT NOT NULL DEFAULT 0,
 billing_failed_count INT NOT NULL DEFAULT 0, conflict_count INT NOT NULL DEFAULT 0,
 amount_cny DECIMAL(24,6) NOT NULL DEFAULT 0,
 error_message VARCHAR(500) NULL, attempts INT NOT NULL DEFAULT 0,
 created_by CHAR(36) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_label_file(organization_id,content_hash),
 INDEX idx_label_import_work(status,created_at),
 INDEX idx_label_import_org(organization_id,status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_label_invoice_rows (
 import_id CHAR(36) NOT NULL, source_row INT NOT NULL, organization_id CHAR(36) NOT NULL,
 event_key CHAR(64) NOT NULL, row_key CHAR(64) NOT NULL, bucket_key CHAR(64) NOT NULL,
 source_priority INT NOT NULL DEFAULT 1, billing_priority INT NOT NULL DEFAULT 2,
 billing_status VARCHAR(64) NOT NULL DEFAULT '计算成功', fee_type VARCHAR(64) NOT NULL DEFAULT '出库费',
 source_sheet VARCHAR(120) NOT NULL DEFAULT '', unit_price_cny DECIMAL(24,6) NULL, billed_quantity DECIMAL(24,6) NULL,
 order_id VARCHAR(255) NOT NULL, shop_hint VARCHAR(255) NOT NULL,
 customer VARCHAR(120) NOT NULL, warehouse VARCHAR(120) NOT NULL, document_no VARCHAR(200) NOT NULL,
 transaction_date DATE NOT NULL, amount_cny DECIMAL(24,6) NOT NULL,
 matched_shop_id CHAR(36) NULL, match_status VARCHAR(24) NOT NULL DEFAULT 'UNMATCHED',
 match_note VARCHAR(250) NOT NULL DEFAULT '',
 match_checked BOOLEAN NOT NULL DEFAULT FALSE, match_revision BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(import_id,source_row),
 UNIQUE KEY uk_label_file_event(import_id,row_key),
 INDEX idx_label_invoice_bucket(organization_id,bucket_key,source_priority,billing_priority),
 INDEX idx_label_invoice_event(organization_id,event_key,matched_shop_id),
 INDEX idx_label_invoice_order(organization_id,order_id,matched_shop_id),
 INDEX idx_label_invoice_shop(organization_id,matched_shop_id,order_id),
 INDEX idx_label_match_cursor(organization_id,matched_shop_id,import_id,source_row),
 INDEX idx_label_unchecked_cursor(organization_id,match_checked,import_id,source_row)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_label_state (
 organization_id CHAR(36) PRIMARY KEY, active_generation CHAR(36) NOT NULL DEFAULT '',
 requested_revision BIGINT NOT NULL DEFAULT 0, applied_revision BIGINT NOT NULL DEFAULT 0,
 source_revision BIGINT NOT NULL DEFAULT 0,
 error_message VARCHAR(500) NULL,
 retry_after DATETIME(6) NULL,
 rematch_all BOOLEAN NOT NULL DEFAULT FALSE,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_label_order_totals (
 organization_id CHAR(36) NOT NULL, generation CHAR(36) NOT NULL,
 shop_id CHAR(36) NOT NULL, order_id VARCHAR(255) NOT NULL,
 amount_cny DECIMAL(24,6) NOT NULL, pending_count INT NOT NULL, shipment_count INT NOT NULL,
 PRIMARY KEY(organization_id,generation,shop_id,order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_label_worker_lock (
 id INT PRIMARY KEY, lease_token CHAR(36) NULL, lease_until DATETIME(6) NULL
) ENGINE=InnoDB;
INSERT INTO finance_label_worker_lock(id) VALUES(1);
