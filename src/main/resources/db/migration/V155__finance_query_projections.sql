-- Persistent native facts. Publishing a pair is atomic; source writes only append compact events.
CREATE TABLE finance_query_partitions (
 organization_id CHAR(36) NOT NULL, platform_filter VARCHAR(24) NOT NULL DEFAULT '',
 partition_month DATE NOT NULL, schema_version INT NOT NULL DEFAULT 1,
 requested_revision BIGINT NOT NULL DEFAULT 1, built_revision BIGINT NOT NULL DEFAULT 0,
 full_rebuild BOOLEAN NOT NULL DEFAULT TRUE,
 -- MySQL's binary JSON parser rounds fractional JSON numbers to DOUBLE. Preserve
 -- decimal text and validate syntax without converting monetary values.
 full_month_json LONGTEXT NULL, partial_first_day_json LONGTEXT NULL, spend_json LONGTEXT NULL,
 generated_at DATETIME(6) NULL, requested_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 lease_token CHAR(36) NULL, lease_until DATETIME(6) NULL,
 next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 failures INT NOT NULL DEFAULT 0, error_code VARCHAR(80) NULL,
 PRIMARY KEY(organization_id,platform_filter,partition_month),
 INDEX idx_finance_query_due(next_attempt_at,lease_until,requested_at),
 CHECK(full_month_json IS NULL OR JSON_VALID(full_month_json)),
 CHECK(partial_first_day_json IS NULL OR JSON_VALID(partial_first_day_json)),
 CHECK(spend_json IS NULL OR JSON_VALID(spend_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_query_dirty_days (
 organization_id CHAR(36) NOT NULL, platform_filter VARCHAR(24) NOT NULL,
 partition_month DATE NOT NULL, record_date DATE NOT NULL,
 PRIMARY KEY(organization_id,platform_filter,partition_month,record_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- No JSON snapshots or credentials. Auto-increment IDs are identities, never commit-order checkpoints.
CREATE TABLE finance_query_events (
 event_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 organization_id CHAR(36) NOT NULL, event_scope VARCHAR(8) NOT NULL,
 record_date DATE NULL, shop_id CHAR(36) NULL, order_id VARCHAR(255) NULL, record_id CHAR(64) NULL,
 source_table VARCHAR(64) NOT NULL, statement_id VARCHAR(255) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX idx_finance_query_events_scope(organization_id,event_id),
 INDEX idx_finance_query_events_date(organization_id,record_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- MySQL 8.4 does not recognize all required ordering parts on the existing 16-column
-- aggregate index. Keep it and add the separately measured 11-column page path.
SET @finance_query_page_sql = (SELECT IF(COUNT(*)=0,
 'CREATE INDEX idx_finance_page ON finance_records (organization_id,kind,record_date DESC,id,shop_id,platform,currency,source,status,category,order_id)',
 'SELECT 1') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='finance_records' AND index_name='idx_finance_page');
PREPARE finance_query_page_stmt FROM @finance_query_page_sql;
EXECUTE finance_query_page_stmt;
DEALLOCATE PREPARE finance_query_page_stmt;

CREATE TRIGGER fin_qp_records_ai AFTER INSERT ON finance_records FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table,statement_id)
 SELECT NEW.organization_id,CASE WHEN NEW.kind IN ('ORDER','REFUND','SETTLEMENT') THEN 'ORDER' ELSE 'DAY' END,NEW.record_date,NEW.shop_id,NEW.order_id,NEW.id,'finance_records',CASE WHEN NEW.kind='BILL' THEN NEW.external_id ELSE NEW.statement_id END;

CREATE TRIGGER fin_qp_records_au AFTER UPDATE ON finance_records FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table,statement_id)
 SELECT OLD.organization_id,CASE WHEN OLD.kind IN ('ORDER','REFUND','SETTLEMENT') THEN 'ORDER' ELSE 'DAY' END,OLD.record_date,OLD.shop_id,OLD.order_id,OLD.id,'finance_records',CASE WHEN OLD.kind='BILL' THEN OLD.external_id ELSE OLD.statement_id END WHERE NOT (OLD.organization_id <=> NEW.organization_id AND OLD.shop_id <=> NEW.shop_id AND OLD.kind <=> NEW.kind AND OLD.record_date <=> NEW.record_date AND OLD.order_id <=> NEW.order_id AND OLD.statement_id <=> NEW.statement_id AND OLD.currency <=> NEW.currency AND OLD.source <=> NEW.source AND OLD.status <=> NEW.status AND OLD.category <=> NEW.category AND OLD.amount <=> NEW.amount AND OLD.revenue <=> NEW.revenue AND OLD.impressions <=> NEW.impressions AND OLD.clicks <=> NEW.clicks AND OLD.conversions <=> NEW.conversions AND OLD.raw_json <=> NEW.raw_json AND OLD.platform <=> NEW.platform)
 UNION ALL SELECT NEW.organization_id,CASE WHEN NEW.kind IN ('ORDER','REFUND','SETTLEMENT') THEN 'ORDER' ELSE 'DAY' END,NEW.record_date,NEW.shop_id,NEW.order_id,NEW.id,'finance_records',CASE WHEN NEW.kind='BILL' THEN NEW.external_id ELSE NEW.statement_id END WHERE NOT (OLD.organization_id <=> NEW.organization_id AND OLD.shop_id <=> NEW.shop_id AND OLD.kind <=> NEW.kind AND OLD.record_date <=> NEW.record_date AND OLD.order_id <=> NEW.order_id AND OLD.statement_id <=> NEW.statement_id AND OLD.currency <=> NEW.currency AND OLD.source <=> NEW.source AND OLD.status <=> NEW.status AND OLD.category <=> NEW.category AND OLD.amount <=> NEW.amount AND OLD.revenue <=> NEW.revenue AND OLD.impressions <=> NEW.impressions AND OLD.clicks <=> NEW.clicks AND OLD.conversions <=> NEW.conversions AND OLD.raw_json <=> NEW.raw_json AND OLD.platform <=> NEW.platform);

CREATE TRIGGER fin_qp_records_ad AFTER DELETE ON finance_records FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table,statement_id)
 SELECT OLD.organization_id,CASE WHEN OLD.kind IN ('ORDER','REFUND','SETTLEMENT') THEN 'ORDER' ELSE 'DAY' END,OLD.record_date,OLD.shop_id,OLD.order_id,OLD.id,'finance_records',CASE WHEN OLD.kind='BILL' THEN OLD.external_id ELSE OLD.statement_id END;

CREATE TRIGGER fin_qp_order_costs_ai AFTER INSERT ON finance_order_costs FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'ORDER',NEW.record_date,NEW.shop_id,NEW.order_id,NEW.order_record_id,'finance_order_costs';

CREATE TRIGGER fin_qp_order_costs_au AFTER UPDATE ON finance_order_costs FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ORDER',OLD.record_date,OLD.shop_id,OLD.order_id,OLD.order_record_id,'finance_order_costs'
 UNION ALL SELECT NEW.organization_id,'ORDER',NEW.record_date,NEW.shop_id,NEW.order_id,NEW.order_record_id,'finance_order_costs';

CREATE TRIGGER fin_qp_order_costs_ad AFTER DELETE ON finance_order_costs FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ORDER',OLD.record_date,OLD.shop_id,OLD.order_id,OLD.order_record_id,'finance_order_costs';

CREATE TRIGGER fin_qp_order_cost_jobs_ai AFTER INSERT ON finance_order_cost_jobs FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'ORDER',NULL,NEW.shop_id,NULL,NEW.order_record_id,'finance_order_cost_jobs';

CREATE TRIGGER fin_qp_order_cost_jobs_au AFTER UPDATE ON finance_order_cost_jobs FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ORDER',NULL,OLD.shop_id,NULL,OLD.order_record_id,'finance_order_cost_jobs' WHERE NOT (OLD.priority <=> NEW.priority)
 UNION ALL SELECT NEW.organization_id,'ORDER',NULL,NEW.shop_id,NULL,NEW.order_record_id,'finance_order_cost_jobs' WHERE NOT (OLD.priority <=> NEW.priority);

CREATE TRIGGER fin_qp_order_cost_jobs_ad AFTER DELETE ON finance_order_cost_jobs FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ORDER',NULL,OLD.shop_id,NULL,OLD.order_record_id,'finance_order_cost_jobs';

CREATE TRIGGER fin_qp_marketing_orders_ai AFTER INSERT ON finance_marketing_orders FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'ORDER',NULL,NEW.shop_id,NEW.order_id,NULL,'finance_marketing_orders';

CREATE TRIGGER fin_qp_marketing_orders_au AFTER UPDATE ON finance_marketing_orders FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ORDER',NULL,OLD.shop_id,OLD.order_id,NULL,'finance_marketing_orders'
 UNION ALL SELECT NEW.organization_id,'ORDER',NULL,NEW.shop_id,NEW.order_id,NULL,'finance_marketing_orders';

CREATE TRIGGER fin_qp_marketing_orders_ad AFTER DELETE ON finance_marketing_orders FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ORDER',NULL,OLD.shop_id,OLD.order_id,NULL,'finance_marketing_orders';

CREATE TRIGGER fin_qp_reporting_months_ai AFTER INSERT ON finance_reporting_months FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'ORDER',NEW.reporting_month,NEW.shop_id,NEW.order_id,NULL,'finance_reporting_months';

CREATE TRIGGER fin_qp_reporting_months_au AFTER UPDATE ON finance_reporting_months FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ORDER',OLD.reporting_month,OLD.shop_id,OLD.order_id,NULL,'finance_reporting_months'
 UNION ALL SELECT NEW.organization_id,'ORDER',NEW.reporting_month,NEW.shop_id,NEW.order_id,NULL,'finance_reporting_months';

CREATE TRIGGER fin_qp_reporting_months_ad AFTER DELETE ON finance_reporting_months FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ORDER',OLD.reporting_month,OLD.shop_id,OLD.order_id,NULL,'finance_reporting_months';

CREATE TRIGGER fin_qp_label_order_totals_ai AFTER INSERT ON finance_label_order_totals FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'ORDER',NULL,NEW.shop_id,NEW.order_id,NULL,'finance_label_order_totals';

CREATE TRIGGER fin_qp_label_order_totals_au AFTER UPDATE ON finance_label_order_totals FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ORDER',NULL,OLD.shop_id,OLD.order_id,NULL,'finance_label_order_totals'
 UNION ALL SELECT NEW.organization_id,'ORDER',NULL,NEW.shop_id,NEW.order_id,NULL,'finance_label_order_totals';

CREATE TRIGGER fin_qp_label_order_totals_ad AFTER DELETE ON finance_label_order_totals FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ORDER',NULL,OLD.shop_id,OLD.order_id,NULL,'finance_label_order_totals';

CREATE TRIGGER fin_qp_actual_order_costs_ai AFTER INSERT ON finance_actual_order_costs FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'ORDER',NULL,NEW.shop_id,NEW.order_id,NEW.order_record_id,'finance_actual_order_costs';

CREATE TRIGGER fin_qp_actual_order_costs_au AFTER UPDATE ON finance_actual_order_costs FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ORDER',NULL,OLD.shop_id,OLD.order_id,OLD.order_record_id,'finance_actual_order_costs'
 UNION ALL SELECT NEW.organization_id,'ORDER',NULL,NEW.shop_id,NEW.order_id,NEW.order_record_id,'finance_actual_order_costs';

CREATE TRIGGER fin_qp_actual_order_costs_ad AFTER DELETE ON finance_actual_order_costs FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ORDER',NULL,OLD.shop_id,OLD.order_id,OLD.order_record_id,'finance_actual_order_costs';

CREATE TRIGGER fin_qp_ad_spend_imports_ai AFTER INSERT ON finance_ad_spend_imports FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'DAY',NEW.record_date,NULL,NULL,NULL,'finance_ad_spend_imports';

CREATE TRIGGER fin_qp_ad_spend_imports_au AFTER UPDATE ON finance_ad_spend_imports FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'DAY',OLD.record_date,NULL,NULL,NULL,'finance_ad_spend_imports'
 UNION ALL SELECT NEW.organization_id,'DAY',NEW.record_date,NULL,NULL,NULL,'finance_ad_spend_imports';

CREATE TRIGGER fin_qp_ad_spend_imports_ad AFTER DELETE ON finance_ad_spend_imports FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'DAY',OLD.record_date,NULL,NULL,NULL,'finance_ad_spend_imports';

CREATE TRIGGER fin_qp_ad_bc_costs_ai AFTER INSERT ON finance_ad_bc_costs FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'DAY',NEW.record_date,NULL,NULL,NULL,'finance_ad_bc_costs';

CREATE TRIGGER fin_qp_ad_bc_costs_au AFTER UPDATE ON finance_ad_bc_costs FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'DAY',OLD.record_date,NULL,NULL,NULL,'finance_ad_bc_costs' WHERE NOT (OLD.amount <=> NEW.amount AND OLD.cash_amount <=> NEW.cash_amount AND OLD.grant_amount <=> NEW.grant_amount AND OLD.tax_amount <=> NEW.tax_amount AND OLD.record_date <=> NEW.record_date AND OLD.currency <=> NEW.currency AND OLD.advertiser_id <=> NEW.advertiser_id AND OLD.advertiser_name <=> NEW.advertiser_name)
 UNION ALL SELECT NEW.organization_id,'DAY',NEW.record_date,NULL,NULL,NULL,'finance_ad_bc_costs' WHERE NOT (OLD.amount <=> NEW.amount AND OLD.cash_amount <=> NEW.cash_amount AND OLD.grant_amount <=> NEW.grant_amount AND OLD.tax_amount <=> NEW.tax_amount AND OLD.record_date <=> NEW.record_date AND OLD.currency <=> NEW.currency AND OLD.advertiser_id <=> NEW.advertiser_id AND OLD.advertiser_name <=> NEW.advertiser_name);

CREATE TRIGGER fin_qp_ad_bc_costs_ad AFTER DELETE ON finance_ad_bc_costs FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'DAY',OLD.record_date,NULL,NULL,NULL,'finance_ad_bc_costs';

CREATE TRIGGER fin_qp_connections_ai AFTER INSERT ON finance_connections FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_connections';

CREATE TRIGGER fin_qp_connections_au AFTER UPDATE ON finance_connections FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_connections' WHERE NOT (OLD.organization_id <=> NEW.organization_id AND OLD.platform <=> NEW.platform AND OLD.status <=> NEW.status AND OLD.enabled <=> NEW.enabled AND OLD.currency <=> NEW.currency AND OLD.timezone <=> NEW.timezone AND OLD.group_id <=> NEW.group_id AND OLD.name <=> NEW.name AND OLD.display_name <=> NEW.display_name)
 UNION ALL SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_connections' WHERE NOT (OLD.organization_id <=> NEW.organization_id AND OLD.platform <=> NEW.platform AND OLD.status <=> NEW.status AND OLD.enabled <=> NEW.enabled AND OLD.currency <=> NEW.currency AND OLD.timezone <=> NEW.timezone AND OLD.group_id <=> NEW.group_id AND OLD.name <=> NEW.name AND OLD.display_name <=> NEW.display_name);

CREATE TRIGGER fin_qp_connections_ad AFTER DELETE ON finance_connections FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_connections';

CREATE TRIGGER fin_qp_groups_ai AFTER INSERT ON finance_groups FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_groups';

CREATE TRIGGER fin_qp_groups_au AFTER UPDATE ON finance_groups FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_groups'
 UNION ALL SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_groups';

CREATE TRIGGER fin_qp_groups_ad AFTER DELETE ON finance_groups FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_groups';

CREATE TRIGGER fin_qp_cost_settings_ai AFTER INSERT ON finance_cost_settings FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_cost_settings';

CREATE TRIGGER fin_qp_cost_settings_au AFTER UPDATE ON finance_cost_settings FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_cost_settings'
 UNION ALL SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_cost_settings';

CREATE TRIGGER fin_qp_cost_settings_ad AFTER DELETE ON finance_cost_settings FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_cost_settings';

CREATE TRIGGER fin_qp_cost_mappings_ai AFTER INSERT ON finance_cost_mappings FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_cost_mappings';

CREATE TRIGGER fin_qp_cost_mappings_au AFTER UPDATE ON finance_cost_mappings FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_cost_mappings'
 UNION ALL SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_cost_mappings';

CREATE TRIGGER fin_qp_cost_mappings_ad AFTER DELETE ON finance_cost_mappings FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_cost_mappings';

CREATE TRIGGER fin_qp_cost_sources_ai AFTER INSERT ON finance_cost_sources FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_cost_sources';

CREATE TRIGGER fin_qp_cost_sources_au AFTER UPDATE ON finance_cost_sources FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_cost_sources'
 UNION ALL SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_cost_sources';

CREATE TRIGGER fin_qp_cost_sources_ad AFTER DELETE ON finance_cost_sources FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_cost_sources';

CREATE TRIGGER fin_qp_manual_costs_ai AFTER INSERT ON finance_manual_costs FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_manual_costs';

CREATE TRIGGER fin_qp_manual_costs_au AFTER UPDATE ON finance_manual_costs FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_manual_costs'
 UNION ALL SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_manual_costs';

CREATE TRIGGER fin_qp_manual_costs_ad AFTER DELETE ON finance_manual_costs FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_manual_costs';

CREATE TRIGGER fin_qp_label_rules_ai AFTER INSERT ON finance_label_rules FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_label_rules';

CREATE TRIGGER fin_qp_label_rules_au AFTER UPDATE ON finance_label_rules FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_label_rules'
 UNION ALL SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_label_rules';

CREATE TRIGGER fin_qp_label_rules_ad AFTER DELETE ON finance_label_rules FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_label_rules';

CREATE TRIGGER fin_qp_charge_rows_ai AFTER INSERT ON finance_charge_rows FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_charge_rows';

CREATE TRIGGER fin_qp_charge_rows_au AFTER UPDATE ON finance_charge_rows FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_charge_rows'
 UNION ALL SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_charge_rows';

CREATE TRIGGER fin_qp_charge_rows_ad AFTER DELETE ON finance_charge_rows FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_charge_rows';

CREATE TRIGGER fin_qp_expenses_ai AFTER INSERT ON finance_expenses FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_expenses';

CREATE TRIGGER fin_qp_expenses_au AFTER UPDATE ON finance_expenses FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_expenses'
 UNION ALL SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_expenses';

CREATE TRIGGER fin_qp_expenses_ad AFTER DELETE ON finance_expenses FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_expenses';

CREATE TRIGGER fin_qp_label_state_ai AFTER INSERT ON finance_label_state FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_label_state';

CREATE TRIGGER fin_qp_label_state_au AFTER UPDATE ON finance_label_state FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_label_state' WHERE NOT (OLD.active_generation <=> NEW.active_generation AND OLD.requested_revision <=> NEW.requested_revision AND OLD.applied_revision <=> NEW.applied_revision AND OLD.source_revision <=> NEW.source_revision)
 UNION ALL SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_label_state' WHERE NOT (OLD.active_generation <=> NEW.active_generation AND OLD.requested_revision <=> NEW.requested_revision AND OLD.applied_revision <=> NEW.applied_revision AND OLD.source_revision <=> NEW.source_revision);

CREATE TRIGGER fin_qp_label_state_ad AFTER DELETE ON finance_label_state FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_label_state';

CREATE TRIGGER fin_qp_ad_accounts_ai AFTER INSERT ON finance_ad_accounts FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_ad_accounts';

CREATE TRIGGER fin_qp_ad_accounts_au AFTER UPDATE ON finance_ad_accounts FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_ad_accounts' WHERE NOT (OLD.organization_id <=> NEW.organization_id AND OLD.advertiser_id <=> NEW.advertiser_id AND OLD.shop_id <=> NEW.shop_id AND OLD.name <=> NEW.name AND OLD.enabled <=> NEW.enabled AND OLD.funding_credential_key <=> NEW.funding_credential_key)
 UNION ALL SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_ad_accounts' WHERE NOT (OLD.organization_id <=> NEW.organization_id AND OLD.advertiser_id <=> NEW.advertiser_id AND OLD.shop_id <=> NEW.shop_id AND OLD.name <=> NEW.name AND OLD.enabled <=> NEW.enabled AND OLD.funding_credential_key <=> NEW.funding_credential_key);

CREATE TRIGGER fin_qp_ad_accounts_ad AFTER DELETE ON finance_ad_accounts FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_ad_accounts';

CREATE TRIGGER fin_qp_ad_stores_ai AFTER INSERT ON finance_ad_stores FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_ad_stores';

CREATE TRIGGER fin_qp_ad_stores_au AFTER UPDATE ON finance_ad_stores FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_ad_stores'
 UNION ALL SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_ad_stores';

CREATE TRIGGER fin_qp_ad_stores_ad AFTER DELETE ON finance_ad_stores FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_ad_stores';

CREATE TRIGGER fin_qp_ad_spend_allocation_sets_ai AFTER INSERT ON finance_ad_spend_allocation_sets FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_ad_spend_allocation_sets';

CREATE TRIGGER fin_qp_ad_spend_allocation_sets_au AFTER UPDATE ON finance_ad_spend_allocation_sets FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_ad_spend_allocation_sets'
 UNION ALL SELECT NEW.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_ad_spend_allocation_sets';

CREATE TRIGGER fin_qp_ad_spend_allocation_sets_ad AFTER DELETE ON finance_ad_spend_allocation_sets FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'ALL',NULL,NULL,NULL,NULL,'finance_ad_spend_allocation_sets';

CREATE TRIGGER fin_qp_ad_allocations_ai AFTER INSERT ON finance_ad_spend_allocations FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,source_table)
 SELECT s.organization_id,'ALL','finance_ad_spend_allocations' FROM finance_ad_spend_allocation_sets s WHERE s.id=NEW.allocation_set_id;

CREATE TRIGGER fin_qp_ad_allocations_au AFTER UPDATE ON finance_ad_spend_allocations FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,source_table)
 SELECT s.organization_id,'ALL','finance_ad_spend_allocations' FROM finance_ad_spend_allocation_sets s WHERE s.id=OLD.allocation_set_id
 UNION ALL SELECT s.organization_id,'ALL','finance_ad_spend_allocations' FROM finance_ad_spend_allocation_sets s WHERE s.id=NEW.allocation_set_id;

CREATE TRIGGER fin_qp_ad_allocations_ad AFTER DELETE ON finance_ad_spend_allocations FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,source_table)
 SELECT s.organization_id,'ALL','finance_ad_spend_allocations' FROM finance_ad_spend_allocation_sets s WHERE s.id=OLD.allocation_set_id;

-- Shopee's independent CPC reports live outside the profit ledger. Observation
-- timestamps alone do not change financial facts or force a historical rebuild.
CREATE TRIGGER fin_qp_shopee_ad_daily_ai AFTER INSERT ON finance_shopee_ad_daily FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT NEW.organization_id,'DAY',NEW.record_date,NULL,NULL,NULL,'finance_shopee_ad_daily';

CREATE TRIGGER fin_qp_shopee_ad_daily_au AFTER UPDATE ON finance_shopee_ad_daily FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'DAY',OLD.record_date,NULL,NULL,NULL,'finance_shopee_ad_daily' WHERE NOT (OLD.organization_id <=> NEW.organization_id AND OLD.external_shop_id <=> NEW.external_shop_id AND OLD.source_key <=> NEW.source_key AND OLD.record_date <=> NEW.record_date AND OLD.currency <=> NEW.currency AND OLD.amount <=> NEW.amount)
 UNION ALL SELECT NEW.organization_id,'DAY',NEW.record_date,NULL,NULL,NULL,'finance_shopee_ad_daily' WHERE NOT (OLD.organization_id <=> NEW.organization_id AND OLD.external_shop_id <=> NEW.external_shop_id AND OLD.source_key <=> NEW.source_key AND OLD.record_date <=> NEW.record_date AND OLD.currency <=> NEW.currency AND OLD.amount <=> NEW.amount);

CREATE TRIGGER fin_qp_shopee_ad_daily_ad AFTER DELETE ON finance_shopee_ad_daily FOR EACH ROW
 INSERT INTO finance_query_events(organization_id,event_scope,record_date,shop_id,order_id,record_id,source_table)
 SELECT OLD.organization_id,'DAY',OLD.record_date,NULL,NULL,NULL,'finance_shopee_ad_daily';
