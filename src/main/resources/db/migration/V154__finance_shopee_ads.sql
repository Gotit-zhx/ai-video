-- Ads Service credentials and reports are independent of ERP grants and profit postings.
CREATE TABLE finance_shopee_ad_grants (
 organization_id CHAR(36) NOT NULL, external_shop_id VARCHAR(30) NOT NULL,
 user_id CHAR(36) NOT NULL, partner_id VARCHAR(30) NOT NULL, token_group_id CHAR(36) NOT NULL,
 name VARCHAR(250) NOT NULL, currency CHAR(3) NOT NULL, timezone VARCHAR(80) NOT NULL,
 authorization_subject_type VARCHAR(20) NOT NULL, authorization_subject_id VARCHAR(30) NOT NULL,
 access_token_encrypted TEXT NOT NULL, refresh_token_encrypted TEXT NULL, access_token_expires_at DATETIME(6) NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE, status VARCHAR(32) NOT NULL DEFAULT 'READY',
 checkpoint_json JSON NULL, next_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 last_success_at DATETIME(6) NULL, error_message VARCHAR(500) NULL, failures INT NOT NULL DEFAULT 0,
 lease_token CHAR(36) NULL, lease_until DATETIME(6) NULL,
 PRIMARY KEY(organization_id,external_shop_id), INDEX idx_shopee_ads_due(next_at,lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Shared rotating credentials need a lease independent of pausing/removing one shop.
CREATE TABLE finance_shopee_ad_groups (
 organization_id CHAR(36) NOT NULL, token_group_id CHAR(36) NOT NULL,
 lease_token CHAR(36) NULL, lease_until DATETIME(6) NULL,
 PRIMARY KEY(organization_id,token_group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_shopee_ad_daily (
 organization_id CHAR(36) NOT NULL, external_shop_id VARCHAR(30) NOT NULL,
 source_key CHAR(64) NOT NULL, record_date DATE NOT NULL, currency CHAR(3) NOT NULL,
 amount DECIMAL(24,6) NOT NULL, source_json JSON NOT NULL,
 observed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(organization_id,external_shop_id,record_date),
 UNIQUE KEY uq_shopee_ad_source(organization_id,source_key), INDEX idx_shopee_ads_period(organization_id,record_date,currency)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT IGNORE INTO finance_api_pacing(platform,channel,interval_seconds) VALUES('SHOPEE_ADS','CPC',5);
