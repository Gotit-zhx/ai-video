CREATE TABLE finance_report_export_gate (
 id TINYINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO finance_report_export_gate VALUES (1);

CREATE TABLE finance_report_export_jobs (
 id CHAR(36) NOT NULL PRIMARY KEY,
 organization_id CHAR(36) NOT NULL, user_id CHAR(36) NOT NULL,
 request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 report_type VARCHAR(24) NOT NULL, selection_json LONGTEXT NOT NULL,
 allow_partial BOOLEAN NOT NULL,
 lane VARCHAR(8) NOT NULL, state VARCHAR(8) NOT NULL DEFAULT 'QUEUED',
 error_message VARCHAR(300) NOT NULL DEFAULT '', receipt_json JSON NULL,
 processed_count BIGINT NOT NULL DEFAULT 0, total_count BIGINT NOT NULL DEFAULT 0,
 file_name VARCHAR(250) NOT NULL DEFAULT '', content_type VARCHAR(100) NOT NULL DEFAULT '',
 stored_name VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
 stored_bytes BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 expires_at DATETIME(6) NOT NULL,
 lease_token CHAR(36) NULL, lease_until DATETIME(6) NULL,
 attempts INT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_finance_export_idempotency (organization_id,user_id,request_key),
 KEY idx_finance_export_owner (organization_id,user_id,created_at),
 KEY idx_finance_export_claim (lane,state,available_at,created_at,id),
 KEY idx_finance_export_expiry (expires_at),
 UNIQUE KEY uk_finance_export_storage (stored_name),
 CHECK (JSON_VALID(selection_json)),
 CHECK (lane IN ('SMALL','STANDARD')),
 CHECK (state IN ('QUEUED','WAITING','RUNNING','READY','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
