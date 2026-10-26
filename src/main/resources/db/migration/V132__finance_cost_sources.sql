CREATE TABLE finance_cost_sources (
 id CHAR(36) PRIMARY KEY, organization_id CHAR(36) NOT NULL, group_id CHAR(36) NOT NULL,
 source_name VARCHAR(250) NOT NULL, content_hash CHAR(64) NOT NULL, data_json JSON NOT NULL,
 source_order BIGINT NOT NULL, legacy_merged BOOLEAN NOT NULL DEFAULT FALSE,
 created_by CHAR(36) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_cost_source_file(organization_id,group_id,content_hash),
 UNIQUE KEY uk_cost_source_order(organization_id,group_id,source_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Old books are cumulative snapshots, not independently removable files.
-- Preserve the latest complete snapshot as one source; keep every old book for audit/export.
INSERT INTO finance_cost_sources
 (id,organization_id,group_id,source_name,content_hash,data_json,source_order,legacy_merged,created_by,created_at)
SELECT id,organization_id,group_id,source_name,content_hash,data_json,1,book_count>1,created_by,created_at
FROM (
 SELECT b.*,ROW_NUMBER() OVER(PARTITION BY organization_id,group_id ORDER BY created_at DESC,id DESC) position,
 COUNT(*) OVER(PARTITION BY organization_id,group_id) book_count
 FROM finance_cost_books b
) previous WHERE position=1;

CREATE TABLE finance_manual_costs (
 id CHAR(36) PRIMARY KEY, organization_id CHAR(36) NOT NULL, group_id CHAR(36) NOT NULL,
 code VARCHAR(255) NOT NULL, warehouse VARCHAR(120) NOT NULL,
 unit VARCHAR(40) NOT NULL, unit_price_cny DECIMAL(24,6) NOT NULL,
 version INT NOT NULL DEFAULT 1, created_by CHAR(36) NOT NULL, updated_by CHAR(36) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX idx_manual_cost_group(organization_id,group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
