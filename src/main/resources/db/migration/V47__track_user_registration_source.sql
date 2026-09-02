ALTER TABLE app_users
    ADD COLUMN registration_source VARCHAR(24) NOT NULL DEFAULT 'LOCAL' AFTER status;

CREATE INDEX idx_app_users_registration_source
    ON app_users (organization_id, registration_source);
