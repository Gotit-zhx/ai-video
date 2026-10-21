CREATE TABLE finance_ad_applications (
 organization_id CHAR(36) NOT NULL, user_id CHAR(36) NOT NULL,
 app_id VARCHAR(64) NOT NULL, secret_encrypted TEXT NOT NULL, revision CHAR(36) NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(organization_id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE finance_authorization_sessions ADD COLUMN context_json JSON NULL;
