CREATE TABLE finance_connections (
 id CHAR(36) PRIMARY KEY, organization_id CHAR(36) NOT NULL, user_id CHAR(36) NOT NULL,
 platform VARCHAR(24) NOT NULL, external_shop_id VARCHAR(128) NOT NULL,
 name VARCHAR(255) NOT NULL, display_name VARCHAR(255) NULL, region VARCHAR(16) NULL,
 merchant_id VARCHAR(128) NULL, shop_cipher VARCHAR(512) NULL, seller_type VARCHAR(64) NULL,
 authorization_subject_type VARCHAR(32) NULL, authorization_subject_id VARCHAR(128) NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE', enabled BOOLEAN NOT NULL DEFAULT FALSE,
 currency VARCHAR(8) NOT NULL, timezone VARCHAR(64) NOT NULL,
 token_group_id CHAR(36) NOT NULL, access_token_encrypted TEXT NOT NULL,
 refresh_token_encrypted TEXT NULL, access_token_expires_at DATETIME(6) NULL,
 refresh_token_expires_at DATETIME(6) NULL, scopes_json JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_finance_connection(organization_id,user_id,platform,external_shop_id),
 INDEX idx_finance_connection_group(organization_id,user_id,token_group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_authorization_sessions (
 id CHAR(36) PRIMARY KEY, organization_id CHAR(36) NOT NULL, user_id CHAR(36) NOT NULL,
 platform VARCHAR(24) NOT NULL, state_hash CHAR(64) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'PENDING', expires_at DATETIME(6) NOT NULL,
 error_message VARCHAR(500) NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_finance_authorization_state(state_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE finance_view_preferences (
 organization_id CHAR(36) NOT NULL, user_id CHAR(36) NOT NULL, view_key VARCHAR(32) NOT NULL,
 preferences_json JSON NOT NULL, updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(organization_id,user_id,view_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Retain legacy finance history for audit; stop using commerce authorizations.
-- No commerce shop or credential is changed, copied, revoked or deleted.
UPDATE finance_shops SET enabled=FALSE,updated_at=UTC_TIMESTAMP(6);
UPDATE finance_ad_accounts SET enabled=FALSE,status='DISABLED'
WHERE COALESCE(shop_id,'')<>'';
