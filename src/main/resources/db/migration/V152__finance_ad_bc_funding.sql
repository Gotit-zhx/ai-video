-- Public BC cost facts remain separate from legacy AD postings and imported funding facts.
ALTER TABLE finance_ad_accounts ADD COLUMN funding_credential_key CHAR(64) NULL;

CREATE TABLE finance_ad_bc_sync (
 organization_id CHAR(36) NOT NULL, credential_key CHAR(64) NOT NULL,
 status VARCHAR(32) NOT NULL DEFAULT 'READY', error_message VARCHAR(500) NULL,
 discovery_page INT NOT NULL DEFAULT 1, discovery_stage_json JSON NULL, bc_json JSON NULL,
 discovery_next_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), checkpoint_json JSON NULL,
 failures INT NOT NULL DEFAULT 0, next_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 last_success_at DATETIME(6) NULL, lease_token CHAR(36) NULL, lease_until DATETIME(6) NULL,
 PRIMARY KEY(organization_id,credential_key), INDEX idx_ad_bc_due(next_at,lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A day is published only after all pages of all authorized finance BCs have completed.
CREATE TABLE finance_ad_bc_stage (
 organization_id CHAR(36) NOT NULL, credential_key CHAR(64) NOT NULL, run_id CHAR(36) NOT NULL,
 bc_id VARCHAR(30) NOT NULL, advertiser_id VARCHAR(30) NOT NULL, record_date DATE NOT NULL,
 currency CHAR(3) NOT NULL, page_number INT NOT NULL, source_json JSON NOT NULL,
 PRIMARY KEY(organization_id,credential_key,run_id,bc_id,advertiser_id,currency)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Natural identity excludes BC and credentials: shared access never multiplies account cost.
CREATE TABLE finance_ad_bc_costs (
 organization_id CHAR(36) NOT NULL, source_key CHAR(64) NOT NULL,
 advertiser_id VARCHAR(30) NOT NULL, advertiser_name VARCHAR(250) NOT NULL,
 record_date DATE NOT NULL, currency CHAR(3) NOT NULL,
 amount DECIMAL(24,6) NOT NULL, cash_amount DECIMAL(24,6) NULL,
 grant_amount DECIMAL(24,6) NULL, tax_amount DECIMAL(24,6) NULL,
 bc_ids_json JSON NOT NULL, observed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(organization_id,source_key),
 UNIQUE KEY uq_ad_bc_account_day(organization_id,advertiser_id,record_date,currency),
 INDEX idx_ad_bc_period(organization_id,record_date,currency)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
