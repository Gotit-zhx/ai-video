ALTER TABLE shops
    ADD COLUMN authorization_subject_type VARCHAR(32) NULL AFTER merchant_id,
    ADD COLUMN authorization_subject_id VARCHAR(128) NULL AFTER authorization_subject_type,
    ADD COLUMN authorized_by CHAR(36) NULL AFTER authorized_scopes,
    ADD COLUMN authorized_at DATETIME(6) NULL AFTER authorized_by,
    ADD COLUMN disconnected_by CHAR(36) NULL AFTER authorized_at,
    ADD COLUMN disconnected_at DATETIME(6) NULL AFTER disconnected_by,
    ADD CONSTRAINT fk_shops_authorized_by
        FOREIGN KEY (authorized_by) REFERENCES app_users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_shops_disconnected_by
        FOREIGN KEY (disconnected_by) REFERENCES app_users(id) ON DELETE SET NULL;

UPDATE shops
   SET authorization_subject_type = CASE
           WHEN platform = 'SHOPEE' THEN 'SHOP'
           WHEN platform = 'TIKTOK_SHOP' THEN 'SELLER'
           ELSE NULL
       END,
       authorization_subject_id = CASE
           WHEN platform = 'SHOPEE' THEN external_shop_id
           WHEN platform = 'TIKTOK_SHOP' THEN merchant_id
           ELSE NULL
       END
 WHERE authorization_subject_type IS NULL;

UPDATE shops
   SET authorized_at = COALESCE(last_synced_at, updated_at, created_at)
 WHERE authorized_at IS NULL
   AND status IN ('ACTIVE', 'TOKEN_EXPIRED');

UPDATE shops
   SET disconnected_at = updated_at
 WHERE disconnected_at IS NULL
   AND status = 'DISABLED';

CREATE INDEX idx_shops_organization_authorized_by
    ON shops(organization_id, authorized_by);

ALTER TABLE shop_credentials
    ADD COLUMN token_group_id CHAR(36) NULL AFTER organization_id;

-- Existing credentials predate authorization-group tracking. Treat each one as
-- an independent grant so the migration cannot accidentally share tokens.
UPDATE shop_credentials
   SET token_group_id = shop_id
 WHERE token_group_id IS NULL;

ALTER TABLE shop_credentials
    MODIFY COLUMN token_group_id CHAR(36) NOT NULL;

CREATE INDEX idx_shop_credentials_org_token_group
    ON shop_credentials(organization_id, token_group_id, shop_id);
