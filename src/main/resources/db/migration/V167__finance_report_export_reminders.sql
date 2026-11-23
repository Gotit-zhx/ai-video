CREATE TABLE finance_report_export_reminders (
 id CHAR(36) PRIMARY KEY,
 organization_id CHAR(36) NOT NULL,
 user_id CHAR(36) NOT NULL,
 selection_key CHAR(64) NOT NULL,
 report_type VARCHAR(24) NOT NULL,
 selection_json JSON NOT NULL,
 state VARCHAR(12) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 next_check_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 ready_at DATETIME(6) NULL,
 expires_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_finance_export_reminder_owner (organization_id,user_id,selection_key),
 INDEX idx_finance_export_reminder_due (state,next_check_at),
 INDEX idx_finance_export_reminder_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
