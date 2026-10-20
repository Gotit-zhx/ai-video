CREATE TABLE finance_shops (
 organization_id CHAR(36) NOT NULL, user_id CHAR(36) NOT NULL, shop_id CHAR(36) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE, currency VARCHAR(8) NOT NULL,
 timezone VARCHAR(64) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(organization_id,user_id,shop_id),
 INDEX idx_finance_enabled(enabled),
 FOREIGN KEY(shop_id) REFERENCES shops(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_sync_states (
 organization_id CHAR(36) NOT NULL, user_id CHAR(36) NOT NULL, shop_id CHAR(36) NOT NULL,
 channel VARCHAR(24) NOT NULL, checkpoint_json JSON NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'READY', error_message VARCHAR(1000) NULL,
 next_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), lease_until DATETIME(6) NULL,
 last_success_at DATETIME(6) NULL, coverage_start DATE NULL, failures INT NOT NULL DEFAULT 0,
 PRIMARY KEY(organization_id,user_id,shop_id,channel),
 INDEX idx_finance_due(next_at,lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_records (
 id CHAR(64) PRIMARY KEY, organization_id CHAR(36) NOT NULL, user_id CHAR(36) NOT NULL,
 shop_id CHAR(36) NOT NULL, platform VARCHAR(24) NOT NULL,
 kind VARCHAR(24) NOT NULL, external_id VARCHAR(200) NOT NULL,
 order_id VARCHAR(128) NOT NULL DEFAULT '', statement_id VARCHAR(128) NOT NULL DEFAULT '',
 category VARCHAR(100) NOT NULL DEFAULT '', status VARCHAR(64) NOT NULL DEFAULT '',
 record_date DATE NOT NULL, currency VARCHAR(8) NOT NULL,
 amount DECIMAL(24,6) NULL, revenue DECIMAL(24,6) NULL,
 impressions BIGINT NULL, clicks BIGINT NULL, conversions DECIMAL(24,6) NULL,
 source VARCHAR(24) NOT NULL, raw_json JSON NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX idx_finance_scope(organization_id,user_id,record_date,kind,currency),
 INDEX idx_finance_shop(organization_id,user_id,shop_id,kind,record_date),
 INDEX idx_finance_bill(organization_id,user_id,shop_id,statement_id,kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_ad_accounts (
 organization_id CHAR(36) NOT NULL, user_id CHAR(36) NOT NULL,
 advertiser_id VARCHAR(64) NOT NULL, name VARCHAR(120) NOT NULL,
 shop_id CHAR(36) NOT NULL DEFAULT '', currency VARCHAR(8) NOT NULL,
 gmv_store_id VARCHAR(64) NOT NULL DEFAULT '', gmv_status VARCHAR(24) NOT NULL DEFAULT 'DISABLED',
 gmv_error VARCHAR(300) NULL, timezone VARCHAR(80) NOT NULL DEFAULT 'UTC',
 access_token_encrypted TEXT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 status VARCHAR(24) NOT NULL DEFAULT 'READY', error_message VARCHAR(1000) NULL,
 next_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), lease_until DATETIME(6) NULL,
 last_success_at DATETIME(6) NULL, checkpoint_json JSON NULL, failures INT NOT NULL DEFAULT 0,
 PRIMARY KEY(organization_id,user_id,advertiser_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_imports (
 id CHAR(36) PRIMARY KEY, organization_id CHAR(36) NOT NULL, user_id CHAR(36) NOT NULL,
 shop_id CHAR(36) NOT NULL, file_name VARCHAR(250) NOT NULL, content_hash CHAR(64) NOT NULL,
 row_count INT NOT NULL, kind VARCHAR(24) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_finance_import(organization_id,user_id,shop_id,kind,content_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Access is restricted by OrganizationAccessPolicy.isPlatformAdmin on every endpoint.
-- No assignable ordinary-admin finance permission is introduced.
CREATE TABLE finance_fx_snapshots (
 id VARCHAR(32) PRIMARY KEY, provider VARCHAR(100) NOT NULL, rate_time DATETIME(6) NOT NULL,
 fetched_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), rates_json JSON NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
