-- Store access is not ownership of all spending by an advertiser.
ALTER TABLE finance_ad_accounts
 ADD discovery_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 ADD discovery_error VARCHAR(300) NULL,
 ADD discovery_next_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 ADD discovery_failures INT NOT NULL DEFAULT 0;

CREATE TABLE finance_ad_stores (
 organization_id CHAR(36) NOT NULL, advertiser_id VARCHAR(64) NOT NULL, store_id VARCHAR(64) NOT NULL,
 store_name VARCHAR(250) NOT NULL DEFAULT '', store_code VARCHAR(100) NOT NULL DEFAULT '',
 shop_id CHAR(36) NOT NULL DEFAULT '', match_status VARCHAR(24) NOT NULL DEFAULT 'UNMATCHED',
 primary_advertiser_id VARCHAR(64) NOT NULL DEFAULT '', gmv_available BOOLEAN NULL,
 report_eligible BOOLEAN NOT NULL DEFAULT TRUE, active BOOLEAN NOT NULL DEFAULT TRUE,
 observed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(organization_id,advertiser_id,store_id), INDEX idx_ad_store_shop(organization_id,shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_ad_streams (
 organization_id CHAR(36) NOT NULL, advertiser_id VARCHAR(64) NOT NULL,
 channel VARCHAR(24) NOT NULL, store_id VARCHAR(64) NOT NULL DEFAULT '',
 status VARCHAR(24) NOT NULL DEFAULT 'READY', error_message VARCHAR(300) NULL,
 next_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), failures INT NOT NULL DEFAULT 0,
 checkpoint_json JSON NULL, last_success_at DATETIME(6) NULL,
 PRIMARY KEY(organization_id,advertiser_id,channel,store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Pages are staged before publishing a complete window. A failed page never clears live records.
CREATE TABLE finance_ad_report_stage (
 organization_id CHAR(36) NOT NULL, advertiser_id VARCHAR(64) NOT NULL,
 channel VARCHAR(24) NOT NULL, store_id VARCHAR(64) NOT NULL, run_id CHAR(36) NOT NULL,
 row_key CHAR(64) NOT NULL, page_number INT NOT NULL, record_date DATE NOT NULL, raw_json JSON NOT NULL,
 PRIMARY KEY(organization_id,advertiser_id,channel,store_id,run_id,row_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Successful daily observations include empty reports, independently of financial records.
CREATE TABLE finance_ad_coverage (
 organization_id CHAR(36) NOT NULL, advertiser_id VARCHAR(64) NOT NULL,
 channel VARCHAR(24) NOT NULL, store_id VARCHAR(64) NOT NULL, record_date DATE NOT NULL,
 row_count INT NOT NULL, amount DECIMAL(24,6) NOT NULL, currency VARCHAR(8) NOT NULL,
 observed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(organization_id,advertiser_id,channel,store_id,record_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
