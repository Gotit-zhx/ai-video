-- Qianyi credentials and resumable read-only after-sales synchronization.
-- Company default credentials can be saved before the remote grants shop-list permission.
CREATE TABLE finance_qianyi_accounts (
 organization_id CHAR(36) PRIMARY KEY, environment VARCHAR(16) NOT NULL,
 app_id VARCHAR(128) NOT NULL, secret_encrypted TEXT NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_qianyi_connections (
 organization_id CHAR(36) NOT NULL, shop_id CHAR(36) NOT NULL,
 environment VARCHAR(16) NOT NULL, app_id VARCHAR(128) NOT NULL,
 secret_encrypted TEXT NOT NULL, account_key CHAR(64) NOT NULL,
 remote_shop_id VARCHAR(40) NOT NULL, remote_shop_name VARCHAR(200) NOT NULL, remote_page INT NOT NULL DEFAULT 1,
 cost_currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
 cost_basis_verified BOOLEAN NOT NULL DEFAULT FALSE,
 enabled BOOLEAN NOT NULL DEFAULT TRUE, sync_start DATE NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1, status VARCHAR(24) NOT NULL DEFAULT 'READY',
 checkpoint_json JSON NULL, last_success_at DATETIME(6) NULL,
 next_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 lease_token CHAR(36) NULL, lease_until DATETIME(6) NULL,
 failures INT NOT NULL DEFAULT 0, error_message VARCHAR(500) NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(organization_id,shop_id),
 UNIQUE KEY uk_qianyi_shop(organization_id,account_key,remote_shop_id),
 INDEX idx_qianyi_work(enabled,next_at,lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_qianyi_returns (
 organization_id CHAR(36) NOT NULL, shop_id CHAR(36) NOT NULL,
 return_number VARCHAR(100) NOT NULL, account_key CHAR(64) NOT NULL,
 order_id VARCHAR(255) NOT NULL DEFAULT '', order_record_id CHAR(64) NULL,
 receipt_date DATE NULL, source_updated_at BIGINT NOT NULL,
 source_hash CHAR(64) NOT NULL, data_json JSON NOT NULL,
 cost_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 cost_note VARCHAR(300) NOT NULL DEFAULT '',
 next_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 attempts INT NOT NULL DEFAULT 0, updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(organization_id,shop_id,return_number),
 INDEX idx_qianyi_return_work(cost_status,next_at),
 INDEX idx_qianyi_shop_work(organization_id,shop_id,cost_status,next_at),
 INDEX idx_qianyi_shop_updated(organization_id,shop_id,source_updated_at,return_number),
 INDEX idx_qianyi_return_order(organization_id,shop_id,order_id),
 INDEX idx_qianyi_return_period(organization_id,shop_id,receipt_date,cost_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A published, fully checked receipt is read by the existing financial reports.
-- One unique Qianyi stock movement can never be counted for two return documents.
CREATE TABLE finance_qianyi_return_costs (
 id CHAR(64) PRIMARY KEY, organization_id CHAR(36) NOT NULL, shop_id CHAR(36) NOT NULL,
 account_key CHAR(64) NOT NULL, movement_id VARCHAR(200) NOT NULL,
 return_number VARCHAR(100) NOT NULL, order_record_id CHAR(64) NOT NULL,
 order_id VARCHAR(255) NOT NULL, receipt_date DATE NOT NULL,
 native_currency VARCHAR(8) NOT NULL, amount_cny DECIMAL(24,6) NOT NULL,
 source_name VARCHAR(250) NOT NULL DEFAULT '千易退货入库',
 data_json JSON NOT NULL, updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_qianyi_return_movement(organization_id,account_key,movement_id),
 INDEX idx_qianyi_cost_period(organization_id,shop_id,receipt_date),
 INDEX idx_qianyi_cost_document(organization_id,shop_id,return_number),
 CONSTRAINT fk_qianyi_cost_order FOREIGN KEY(order_record_id) REFERENCES finance_records(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
