-- Funding facts are independent of authentication credentials and legacy profit postings.
CREATE TABLE finance_ad_spend_imports (
 organization_id CHAR(36) NOT NULL, source_key CHAR(64) NOT NULL,
 platform VARCHAR(24) NOT NULL, account_id VARCHAR(64) NOT NULL, account_name VARCHAR(250) NOT NULL,
 campaign_id VARCHAR(120) NOT NULL, campaign_name VARCHAR(500) NOT NULL DEFAULT '', report_type VARCHAR(80) NOT NULL DEFAULT '',
 record_date DATE NOT NULL, currency CHAR(3) NOT NULL,
 amount DECIMAL(24,6) NOT NULL, cash_amount DECIMAL(24,6) NULL, credit_amount DECIMAL(24,6) NULL, grant_amount DECIMAL(24,6) NULL,
 source_json JSON NOT NULL, file_name VARCHAR(250) NOT NULL, created_by CHAR(36) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(organization_id,source_key), INDEX idx_ad_spend_period(organization_id,record_date,platform,currency)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Explicit assignments are append-only revisions. Changed source amounts require review.
CREATE TABLE finance_ad_spend_allocation_sets (
 id CHAR(36) NOT NULL PRIMARY KEY, organization_id CHAR(36) NOT NULL, source_key CHAR(64) NOT NULL,
 revision INT NOT NULL, fingerprint CHAR(64) NOT NULL, created_by CHAR(36) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_ad_spend_revision(organization_id,source_key,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE finance_ad_spend_allocations (
 allocation_set_id CHAR(36) NOT NULL, shop_id CHAR(36) NOT NULL,
 amount DECIMAL(24,6) NOT NULL, cash_amount DECIMAL(24,6) NULL, credit_amount DECIMAL(24,6) NULL, grant_amount DECIMAL(24,6) NULL,
 PRIMARY KEY(allocation_set_id,shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
