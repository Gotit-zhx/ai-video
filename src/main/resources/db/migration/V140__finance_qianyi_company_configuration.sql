-- One company-wide authorization; bounded background discovery binds exact
-- platform shop IDs, including shops added after the initial configuration.
ALTER TABLE finance_qianyi_accounts
 ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE,
 ADD COLUMN sync_start DATE NULL,
 ADD COLUMN revision BIGINT NOT NULL DEFAULT 1,
 ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'READY',
 ADD COLUMN next_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 ADD COLUMN last_success_at DATETIME(6) NULL,
 ADD COLUMN lease_token CHAR(36) NULL,
 ADD COLUMN lease_until DATETIME(6) NULL,
 ADD COLUMN failures INT NOT NULL DEFAULT 0,
 ADD COLUMN error_message VARCHAR(500) NULL,
 ADD INDEX idx_qianyi_account_work(enabled,next_at,lease_until);
UPDATE finance_qianyi_accounts a
 SET sync_start=COALESCE((SELECT MIN(q.sync_start) FROM finance_qianyi_connections q
   WHERE q.organization_id=a.organization_id),DATE_SUB(DATE_FORMAT(CURRENT_DATE,'%Y-%m-01'),INTERVAL 3 MONTH));

-- A resumable history pass adds newly retained upstream fields, including refundStatus.
ALTER TABLE finance_qianyi_connections ADD COLUMN source_version INT NOT NULL DEFAULT 1;
-- Index raw upstream states independently; no status translation is persisted.
ALTER TABLE finance_qianyi_returns
 ADD COLUMN return_state VARCHAR(64) GENERATED ALWAYS AS
   (COALESCE(NULLIF(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.returnStatus')),'null'),''),
     NULLIF(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.status')),'null'),'')) STORED,
 ADD COLUMN refund_state VARCHAR(64) GENERATED ALWAYS AS
   (COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.refundStatus')),'null'),'')) STORED,
 ADD COLUMN aftersale_type VARCHAR(64) GENERATED ALWAYS AS
   (COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.type')),'null'),'')) STORED,
 ADD COLUMN source_created_at BIGINT GENERATED ALWAYS AS
   (CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.createTime')),'null') AS UNSIGNED)) STORED,
 ADD INDEX idx_qianyi_company_receipt(organization_id,receipt_date,shop_id,return_number),
 ADD INDEX idx_qianyi_company_created(organization_id,source_created_at,shop_id,return_number),
 ADD INDEX idx_qianyi_company_updated(organization_id,source_updated_at,shop_id,return_number),
 ADD INDEX idx_qianyi_company_state(organization_id,return_state,refund_state,aftersale_type);

-- Application-wide refund request budget, preserved across restarts and manual sync.
CREATE TABLE finance_api_pacing (
 platform VARCHAR(32) NOT NULL, channel VARCHAR(32) NOT NULL,
 next_allowed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 interval_seconds INT NOT NULL DEFAULT 5,
 throttle_count INT NOT NULL DEFAULT 0,
 success_count INT NOT NULL DEFAULT 0,
 PRIMARY KEY(platform,channel)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO finance_api_pacing(platform,channel) VALUES('TIKTOK_SHOP','REFUND');

-- Heartbeat/status reads count only pending API bills, without scanning every bill.
-- Online secondary-index creation keeps order writes available.
ALTER TABLE finance_records
 DROP INDEX idx_finance_dashboard,
 ADD INDEX idx_finance_shop_dashboard(
   organization_id,shop_id,record_date,platform,kind,currency,source,
   amount,revenue,impressions,clicks,conversions,category,status),
 ADD INDEX idx_finance_bill_status(organization_id,kind,status,source,shop_id),
 ALGORITHM=INPLACE, LOCK=NONE;
