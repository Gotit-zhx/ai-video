ALTER TABLE shops
    DROP INDEX uk_shops_platform_external,
    ADD COLUMN merchant_id VARCHAR(128) NULL AFTER external_shop_id,
    ADD COLUMN shop_cipher VARCHAR(512) NULL AFTER merchant_id,
    ADD COLUMN seller_type VARCHAR(64) NULL AFTER shop_cipher,
    ADD COLUMN authorized_scopes JSON NULL AFTER credentials_secret_ref,
    ADD COLUMN last_synced_at DATETIME(6) NULL AFTER authorized_scopes,
    ADD COLUMN last_auth_error VARCHAR(1000) NULL AFTER last_synced_at,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER last_auth_error,
    ADD CONSTRAINT uk_shops_org_platform_external
        UNIQUE (organization_id, platform, external_shop_id);

CREATE TABLE shop_credentials (
    shop_id CHAR(36) PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    access_token_ciphertext TEXT NOT NULL,
    refresh_token_ciphertext TEXT,
    access_token_expires_at DATETIME(6),
    refresh_token_expires_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_shop_credentials_shop
        FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE,
    CONSTRAINT fk_shop_credentials_organization
        FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_shop_credentials_organization
    ON shop_credentials(organization_id);

CREATE TABLE shop_authorization_sessions (
    id CHAR(36) PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    created_by CHAR(36) NOT NULL,
    platform VARCHAR(32) NOT NULL,
    state_hash CHAR(64) NOT NULL,
    callback_url VARCHAR(1000) NOT NULL,
    return_path VARCHAR(1000) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6),
    error_message VARCHAR(1000),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_shop_authorization_sessions_state UNIQUE (state_hash),
    CONSTRAINT ck_shop_authorization_sessions_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'EXPIRED')
    ),
    CONSTRAINT fk_shop_authorization_sessions_organization
        FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE,
    CONSTRAINT fk_shop_authorization_sessions_user
        FOREIGN KEY (created_by) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_shop_authorization_sessions_expiry
    ON shop_authorization_sessions(status, expires_at);
