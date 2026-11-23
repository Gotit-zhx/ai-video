CREATE TABLE finance_bill_import_gate (
 id TINYINT PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO finance_bill_import_gate(id) VALUES (1);

CREATE TABLE finance_bill_import_jobs (
 id CHAR(36) PRIMARY KEY, organization_id CHAR(36) NOT NULL, actor_id CHAR(36) NOT NULL,
 filename VARCHAR(250) NOT NULL, byte_count BIGINT NOT NULL,
 shop_id CHAR(36), ledger_user_id CHAR(36), platform VARCHAR(24),
 state VARCHAR(24) NOT NULL, preview_json JSON, mapping_json JSON, content_hash CHAR(64),
 row_count INT NOT NULL DEFAULT 0, duplicate_file BOOLEAN NOT NULL DEFAULT FALSE,
 lease_token CHAR(36), lease_until DATETIME(6), failures INT NOT NULL DEFAULT 0,
 message VARCHAR(500), next_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 expires_at DATETIME(6) NOT NULL, file_cleaned BOOLEAN NOT NULL DEFAULT FALSE,
 INDEX idx_bill_import_jobs_owner(organization_id,actor_id,created_at,id),
 INDEX idx_bill_import_jobs_due(state,next_at,lease_until,id),
 INDEX idx_bill_import_jobs_expiry(expires_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_bill_import_stage (
 job_id CHAR(36) NOT NULL, source_row INT NOT NULL, record_id CHAR(64) NOT NULL,
 external_id VARCHAR(128) NOT NULL, order_id VARCHAR(128) NOT NULL, category VARCHAR(100) NOT NULL,
 record_date DATE NOT NULL, currency VARCHAR(8) NOT NULL, amount DECIMAL(24,6) NOT NULL, raw_json JSON NOT NULL,
 PRIMARY KEY(job_id,source_row),
 CONSTRAINT fk_bill_import_stage_job FOREIGN KEY(job_id) REFERENCES finance_bill_import_jobs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
