CREATE TABLE bd_records (
 id CHAR(36) PRIMARY KEY,
 organization_id CHAR(36) NOT NULL,
 kind VARCHAR(16) NOT NULL,
 creator_id CHAR(36) NOT NULL,
 owner_id CHAR(36) NOT NULL,
 influencer_id CHAR(36),
 identity_key VARCHAR(255),
 payload JSON NOT NULL,
 version INT NOT NULL DEFAULT 1,
 deleted BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_bd_identity (organization_id, kind, identity_key),
 KEY idx_bd_company (organization_id, kind, deleted),
 KEY idx_bd_influencer (organization_id, influencer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE bd_followups (
 id CHAR(36) PRIMARY KEY,
 organization_id CHAR(36) NOT NULL,
 influencer_id CHAR(36) NOT NULL,
 author_id CHAR(36) NOT NULL,
 author_name VARCHAR(120) NOT NULL,
 channel VARCHAR(40) NOT NULL,
 summary TEXT NOT NULL,
 next_date DATE,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 KEY idx_bd_followup (organization_id, influencer_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
