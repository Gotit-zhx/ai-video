CREATE TABLE finance_cost_progress_companies (
 organization_id CHAR(36) PRIMARY KEY,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 changed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Changing reporting mode fences all previously built financial snapshots.
CREATE TRIGGER fin_qp_cost_progress_company_ai AFTER INSERT ON finance_cost_progress_companies FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,source_table)
 SELECT NEW.organization_id,'ALL','finance_cost_progress_companies' WHERE NEW.enabled;

CREATE TRIGGER fin_qp_cost_progress_company_au AFTER UPDATE ON finance_cost_progress_companies FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,source_table)
 SELECT NEW.organization_id,'ALL','finance_cost_progress_companies' WHERE NOT (OLD.enabled <=> NEW.enabled);

CREATE TRIGGER fin_qp_cost_progress_company_ad AFTER DELETE ON finance_cost_progress_companies FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,source_table)
 SELECT OLD.organization_id,'ALL','finance_cost_progress_companies' WHERE OLD.enabled;

DROP TRIGGER fin_qp_order_cost_jobs_ai;
DROP TRIGGER fin_qp_order_cost_jobs_au;
DROP TRIGGER fin_qp_order_cost_jobs_ad;

CREATE TRIGGER fin_qp_order_cost_jobs_ai AFTER INSERT ON finance_order_cost_jobs FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'ORDER',NULL,NEW.shop_id,NULL,NEW.order_record_id,'finance_order_cost_jobs'
 WHERE NOT EXISTS(SELECT 1 FROM finance_cost_progress_companies WHERE organization_id=NEW.organization_id AND enabled);

CREATE TRIGGER fin_qp_order_cost_jobs_au AFTER UPDATE ON finance_order_cost_jobs FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ORDER',NULL,OLD.shop_id,NULL,OLD.order_record_id,'finance_order_cost_jobs'
 WHERE NOT (OLD.priority <=> NEW.priority)
 AND NOT EXISTS(SELECT 1 FROM finance_cost_progress_companies WHERE organization_id=OLD.organization_id AND enabled)
 UNION ALL
 SELECT NEW.organization_id,'ORDER',NULL,NEW.shop_id,NULL,NEW.order_record_id,'finance_order_cost_jobs'
 WHERE NOT (OLD.priority <=> NEW.priority)
 AND NOT EXISTS(SELECT 1 FROM finance_cost_progress_companies WHERE organization_id=NEW.organization_id AND enabled);

CREATE TRIGGER fin_qp_order_cost_jobs_ad AFTER DELETE ON finance_order_cost_jobs FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ORDER',NULL,OLD.shop_id,NULL,OLD.order_record_id,'finance_order_cost_jobs'
 WHERE NOT EXISTS(SELECT 1 FROM finance_cost_progress_companies WHERE organization_id=OLD.organization_id AND enabled);
