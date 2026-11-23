-- Roll out only after the existing source-write gate has drained in-flight work.
-- The trigger copies invalidations in the source transaction and never uses a commit watermark.
CREATE TABLE finance_dashboard_order_companies (
 organization_id CHAR(36) PRIMARY KEY,
 enabled BOOLEAN NOT NULL DEFAULT TRUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_dashboard_order_worker_state (
 organization_id CHAR(36) PRIMARY KEY,
 eligible_from DATE NULL,
 eligible_until DATE NULL,
 checked_at DATETIME(6) NOT NULL DEFAULT '1970-01-01 00:00:01'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_dashboard_order_days (
 organization_id CHAR(36) NOT NULL,
 record_date DATE NOT NULL,
 ready BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(organization_id,record_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_dashboard_order_events (
 event_id BIGINT PRIMARY KEY,
 organization_id CHAR(36) NOT NULL,
 event_scope VARCHAR(8) NOT NULL,
 shop_id CHAR(36) NULL,
 order_id VARCHAR(255) NULL,
 record_id CHAR(64) NULL,
 KEY idx_dashboard_order_events(organization_id,event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_dashboard_order_values (
 organization_id CHAR(36) NOT NULL,
 shop_id CHAR(36) NOT NULL,
 order_id VARCHAR(128) NOT NULL,
 member_id CHAR(64) NOT NULL,
 record_date DATE NOT NULL,
 platform VARCHAR(24) NOT NULL,
 currency VARCHAR(8) NOT NULL,
 status VARCHAR(24) GENERATED ALWAYS AS ('SETTLED') VIRTUAL,
 fx_date DATE NULL,
 order_currency VARCHAR(8) NULL,
 order_status VARCHAR(64) NULL,
 records BIGINT NOT NULL,
 known_amounts BIGINT NOT NULL,
 known_revenues BIGINT NOT NULL,
 amount DECIMAL(65,6) NULL,
 revenue DECIMAL(65,6) NULL,
 impressions DECIMAL(65,0) NULL,
 clicks DECIMAL(65,0) NULL,
 conversions DECIMAL(65,6) NULL,
 order_count BIGINT NOT NULL,
 missing_order_count BIGINT NOT NULL,
 excluded_count BIGINT NOT NULL,
 ready_count BIGINT NOT NULL,
 actual_count BIGINT NOT NULL,
 estimated_count BIGINT NOT NULL,
 pending_count BIGINT NOT NULL,
 queued_count BIGINT NOT NULL,
 product_queued_count BIGINT NOT NULL,
 label_queued_count BIGINT NOT NULL,
 known_amount_cny DECIMAL(65,6) NOT NULL,
 pending_known_amount_cny DECIMAL(65,6) NOT NULL,
 label_cost_cny DECIMAL(65,6) NOT NULL,
 charges_pending BIGINT NOT NULL,
 label_matched_count BIGINT NOT NULL,
 label_missing_count BIGINT NOT NULL,
 cost_before_pending DECIMAL(65,6) NOT NULL,
 PRIMARY KEY(organization_id,shop_id,order_id,record_date,member_id),
 KEY idx_dashboard_order_day(organization_id,record_date,shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TRIGGER fin_dashboard_order_policy_ai AFTER INSERT ON finance_dashboard_order_companies FOR EACH ROW
 UPDATE finance_dashboard_order_days SET ready=FALSE WHERE organization_id=NEW.organization_id;

CREATE TRIGGER fin_dashboard_order_policy_au AFTER UPDATE ON finance_dashboard_order_companies FOR EACH ROW
 UPDATE finance_dashboard_order_days SET ready=FALSE
 WHERE organization_id=NEW.organization_id AND OLD.enabled<>NEW.enabled;

CREATE TRIGGER fin_dashboard_order_event_ai AFTER INSERT ON finance_query_events FOR EACH ROW
 INSERT INTO finance_dashboard_order_events(event_id,organization_id,event_scope,shop_id,order_id,record_id)
 SELECT NEW.event_id,NEW.organization_id,NEW.event_scope,NEW.shop_id,NEW.order_id,NEW.record_id
 FROM finance_dashboard_order_companies c
 WHERE c.organization_id=NEW.organization_id AND c.enabled AND NEW.event_scope IN ('ORDER','ALL');
