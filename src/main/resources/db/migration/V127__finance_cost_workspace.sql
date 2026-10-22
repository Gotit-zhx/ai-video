CREATE TABLE finance_cost_books (
 id CHAR(36) PRIMARY KEY, organization_id CHAR(36) NOT NULL, group_id CHAR(36) NOT NULL,
 source_name VARCHAR(250) NOT NULL, content_hash CHAR(64) NOT NULL, data_json JSON NOT NULL,
 created_by CHAR(36) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_cost_import(organization_id,group_id,content_hash),
 INDEX idx_cost_book(organization_id,group_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE finance_cost_settings (
 organization_id CHAR(36) NOT NULL, shop_id CHAR(36) NOT NULL, data_json JSON NOT NULL,
 version INT NOT NULL DEFAULT 1, updated_by CHAR(36) NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(organization_id,shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE finance_cost_mappings (
 id CHAR(64) PRIMARY KEY, organization_id CHAR(36) NOT NULL, shop_id CHAR(36) NOT NULL,
 product_id VARCHAR(128) NOT NULL, sku_id VARCHAR(128) NOT NULL, warehouse VARCHAR(120) NOT NULL,
 data_json JSON NOT NULL, version INT NOT NULL DEFAULT 1, updated_by CHAR(36) NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX idx_cost_mapping_shop(organization_id,shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE finance_expenses (
 id CHAR(36) PRIMARY KEY, organization_id CHAR(36) NOT NULL,
 scope VARCHAR(16) NOT NULL, target_id CHAR(36) NOT NULL, order_id VARCHAR(128) NOT NULL DEFAULT '',
 category VARCHAR(40) NOT NULL, source_ref VARCHAR(200) NOT NULL, dedupe_key CHAR(64) NOT NULL,
 amount DECIMAL(24,6) NOT NULL, currency VARCHAR(8) NOT NULL,
 start_date DATE NOT NULL, end_date DATE NOT NULL, record_date DATE NOT NULL,
 treatment VARCHAR(24) NOT NULL, data_json JSON NOT NULL, version INT NOT NULL DEFAULT 1,
 created_by CHAR(36) NOT NULL, updated_by CHAR(36) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_expense_source(organization_id,dedupe_key),
 INDEX idx_expense_range(organization_id,record_date,currency),
 INDEX idx_expense_target(organization_id,scope,target_id,record_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE finance_expense_rules (
 id CHAR(36) PRIMARY KEY, organization_id CHAR(36) NOT NULL, data_json JSON NOT NULL,
 created_by CHAR(36) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX idx_expense_rule(organization_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE finance_workspace_audit (
 id CHAR(36) PRIMARY KEY, organization_id CHAR(36) NOT NULL, entity_type VARCHAR(32) NOT NULL,
 entity_id VARCHAR(64) NOT NULL, before_json JSON NULL, after_json JSON NOT NULL,
 reason VARCHAR(1000) NOT NULL, actor_id CHAR(36) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX idx_workspace_audit(organization_id,entity_type,created_at),
 INDEX idx_workspace_entity(organization_id,entity_type,entity_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
