CREATE TABLE social_login_identities (
    id CHAR(36) PRIMARY KEY,
    provider VARCHAR(24) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    user_id CHAR(36) NOT NULL,
    email VARCHAR(320),
    display_name VARCHAR(160),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_social_login_provider_subject UNIQUE (provider, provider_subject),
    CONSTRAINT fk_social_login_identity_user
        FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_social_login_identity_user ON social_login_identities(user_id);
