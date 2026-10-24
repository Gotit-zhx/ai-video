CREATE TABLE finance_shop_deletions (
 organization_id CHAR(36) NOT NULL, shop_id CHAR(36) NOT NULL,
 identity_hash CHAR(64) NOT NULL, shop_label VARCHAR(255) NOT NULL DEFAULT '',
 status VARCHAR(16) NOT NULL DEFAULT 'QUEUED', stage INT NOT NULL DEFAULT 0,
 deleted_rows BIGINT NOT NULL DEFAULT 0, failures INT NOT NULL DEFAULT 0,
 requested_by CHAR(36) NOT NULL, requested_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 next_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), completed_at DATETIME(6) NULL,
 PRIMARY KEY(organization_id,shop_id),
 INDEX idx_finance_deletion_due(status,next_at),
 INDEX idx_finance_deletion_identity(organization_id,identity_hash,requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE finance_shop_deletion_ads (
 organization_id CHAR(36) NOT NULL, advertiser_hash CHAR(64) NOT NULL, shop_id CHAR(36) NOT NULL,
 PRIMARY KEY(organization_id,advertiser_hash,shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
ALTER TABLE finance_order_cost_jobs ADD INDEX idx_cost_job_shop(organization_id,shop_id), ALGORITHM=INPLACE, LOCK=NONE;
ALTER TABLE finance_ad_accounts
 ADD COLUMN lease_token CHAR(36) NULL,
 ADD INDEX idx_finance_ad_shop(organization_id,shop_id),
 ALGORITHM=INPLACE, LOCK=NONE;
