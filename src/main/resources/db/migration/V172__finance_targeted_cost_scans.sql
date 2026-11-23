CREATE TABLE finance_cost_target_companies (
 organization_id CHAR(36) NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_cost_target_scans (
 organization_id CHAR(36) NOT NULL, shop_id CHAR(36) NOT NULL,
 cursor_id CHAR(64) NOT NULL DEFAULT '',
 active_targets JSON NOT NULL, next_targets JSON NOT NULL,
 next_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY (organization_id,shop_id),
 KEY idx_cost_target_due (next_at,organization_id,shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Start with an empty trial list. The active application must schedule scoped
-- order changes before a company opts out of the old configuration events.
DROP TRIGGER fin_qp_cost_mappings_ai;
DROP TRIGGER fin_qp_cost_mappings_au;
DROP TRIGGER fin_qp_cost_mappings_ad;
DROP TRIGGER fin_qp_manual_costs_ai;
DROP TRIGGER fin_qp_manual_costs_au;
DROP TRIGGER fin_qp_manual_costs_ad;

CREATE TRIGGER fin_qp_cost_mappings_ai AFTER INSERT ON finance_cost_mappings FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_cost_mappings'
 WHERE NOT EXISTS (SELECT 1 FROM finance_cost_target_companies WHERE organization_id=NEW.organization_id);

CREATE TRIGGER fin_qp_cost_mappings_au AFTER UPDATE ON finance_cost_mappings FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_cost_mappings'
 WHERE NOT EXISTS (SELECT 1 FROM finance_cost_target_companies WHERE organization_id=OLD.organization_id)
 UNION ALL SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_cost_mappings'
 WHERE NOT EXISTS (SELECT 1 FROM finance_cost_target_companies WHERE organization_id=NEW.organization_id);

CREATE TRIGGER fin_qp_cost_mappings_ad AFTER DELETE ON finance_cost_mappings FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_cost_mappings'
 WHERE NOT EXISTS (SELECT 1 FROM finance_cost_target_companies WHERE organization_id=OLD.organization_id);

CREATE TRIGGER fin_qp_manual_costs_ai AFTER INSERT ON finance_manual_costs FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_manual_costs'
 WHERE NOT EXISTS (SELECT 1 FROM finance_cost_target_companies WHERE organization_id=NEW.organization_id);

CREATE TRIGGER fin_qp_manual_costs_au AFTER UPDATE ON finance_manual_costs FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_manual_costs'
 WHERE NOT EXISTS (SELECT 1 FROM finance_cost_target_companies WHERE organization_id=OLD.organization_id)
 UNION ALL SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_manual_costs'
 WHERE NOT EXISTS (SELECT 1 FROM finance_cost_target_companies WHERE organization_id=NEW.organization_id);

CREATE TRIGGER fin_qp_manual_costs_ad AFTER DELETE ON finance_manual_costs FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_manual_costs'
 WHERE NOT EXISTS (SELECT 1 FROM finance_cost_target_companies WHERE organization_id=OLD.organization_id);
