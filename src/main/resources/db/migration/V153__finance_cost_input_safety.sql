-- Explicit human cost changes only. Original uploads are retained once and never exposed by read APIs.
CREATE TABLE finance_cost_input_changes (
 id CHAR(36) PRIMARY KEY,
 organization_id CHAR(36) NOT NULL, group_id CHAR(36) NOT NULL, shop_id CHAR(36) NOT NULL DEFAULT '',
 type VARCHAR(24) NOT NULL, status VARCHAR(16) NOT NULL, target_id VARCHAR(128) NOT NULL DEFAULT '',
 source_name VARCHAR(250) NOT NULL DEFAULT '', payload_json JSON NOT NULL, metadata_json JSON NOT NULL,
 original_bytes MEDIUMBLOB NULL, original_hash CHAR(64) NULL, original_change_id CHAR(36) NULL,
 semantic_hash CHAR(64) NOT NULL, baseline_hash CHAR(64) NOT NULL,
 validation_json JSON NOT NULL, diff_json JSON NOT NULL, impact_json JSON NOT NULL,
 state_json JSON NOT NULL, after_hash CHAR(64) NULL, result_json JSON NULL,
 requires_independent_review BOOLEAN NOT NULL DEFAULT FALSE,
 submitted_by CHAR(36) NOT NULL, last_edited_by CHAR(36) NOT NULL, confirmed_by CHAR(36) NULL,
 reason VARCHAR(2000) NOT NULL DEFAULT '', evidence_ref VARCHAR(2000) NOT NULL DEFAULT '',
 version BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), applied_at DATETIME(6) NULL,
 INDEX idx_cost_input_list(organization_id,group_id,shop_id,created_at),
 INDEX idx_cost_input_pending(organization_id,status),
 INDEX idx_cost_input_original(organization_id,original_hash),
 INDEX idx_cost_input_semantic(organization_id,group_id,semantic_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
