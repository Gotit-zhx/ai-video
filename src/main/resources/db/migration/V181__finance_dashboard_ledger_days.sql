CREATE TABLE finance_dashboard_ledger_worker_state (
 organization_id CHAR(36) PRIMARY KEY,
 eligible_from DATE NULL,
 eligible_until DATE NULL,
 checked_at DATETIME(6) NOT NULL DEFAULT '1970-01-01 00:00:01'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_dashboard_ledger_days (
 organization_id CHAR(36) NOT NULL,
 record_date DATE NOT NULL,
 ready BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(organization_id,record_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_dashboard_ledger_changes (
 organization_id CHAR(36) NOT NULL,
 record_date DATE NOT NULL,
 shop_id CHAR(36) NOT NULL,
 event_id BIGINT NOT NULL,
 PRIMARY KEY(organization_id,record_date,shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_dashboard_ledger_values (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 organization_id CHAR(36) NOT NULL,
 record_date DATE NOT NULL,
 shop_id CHAR(36) NOT NULL,
 platform VARCHAR(24) NOT NULL,
 currency VARCHAR(8) NOT NULL,
 kind VARCHAR(24) NOT NULL,
 category VARCHAR(100) NOT NULL,
 status VARCHAR(64) GENERATED ALWAYS AS ('') VIRTUAL,
 records BIGINT NOT NULL,
 known_amounts BIGINT NOT NULL,
 known_revenues BIGINT NOT NULL,
 known_impressions BIGINT NOT NULL,
 known_clicks BIGINT NOT NULL,
 known_conversions BIGINT NOT NULL,
 amount DECIMAL(65,6) NULL,
 revenue DECIMAL(65,6) NULL,
 impressions DECIMAL(65,0) NULL,
 clicks DECIMAL(65,0) NULL,
 conversions DECIMAL(65,6) NULL,
 KEY idx_dashboard_ledger_day(organization_id,record_date,shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TRIGGER fin_dashboard_ledger_policy_ai AFTER INSERT ON finance_dashboard_order_companies FOR EACH ROW
 UPDATE finance_dashboard_ledger_days SET ready=FALSE WHERE organization_id=NEW.organization_id;

CREATE TRIGGER fin_dashboard_ledger_policy_au AFTER UPDATE ON finance_dashboard_order_companies FOR EACH ROW
 UPDATE finance_dashboard_ledger_days SET ready=FALSE
 WHERE organization_id=NEW.organization_id AND OLD.enabled<>NEW.enabled;

-- Each marker replaces the last committed one for its shop and day. IDs are not commit watermarks.
CREATE TRIGGER fin_dashboard_ledger_event_ai AFTER INSERT ON finance_query_events FOR EACH ROW
 INSERT INTO finance_dashboard_ledger_changes(organization_id,record_date,shop_id,event_id)
 SELECT NEW.organization_id,NEW.record_date,NEW.shop_id,NEW.event_id
 FROM finance_dashboard_order_companies c
 WHERE c.organization_id=NEW.organization_id AND c.enabled
   AND NEW.source_table='finance_records' AND NEW.record_date IS NOT NULL AND NEW.shop_id IS NOT NULL
 ON DUPLICATE KEY UPDATE event_id=NEW.event_id;
