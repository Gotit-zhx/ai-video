CREATE TABLE product_captures (
    id                  CHAR(36)      NOT NULL,
    organization_id     CHAR(36)      NOT NULL,
    created_by          CHAR(36)      NOT NULL,
    idempotency_key     VARCHAR(128)  NOT NULL,
    request_fingerprint CHAR(64)      NOT NULL,
    source_platform     VARCHAR(24)   NOT NULL,
    source_url          TEXT          NOT NULL,
    source_shop_id      VARCHAR(128)  NULL,
    source_product_id   VARCHAR(128)  NOT NULL,
    status              VARCHAR(32)   NOT NULL,
    title               VARCHAR(500)  NOT NULL,
    description         LONGTEXT      NULL,
    category_name       VARCHAR(255)  NULL,
    brand_name          VARCHAR(255)  NULL,
    currency            VARCHAR(12)   NULL,
    price_min           DECIMAL(14,2) NULL,
    price_max           DECIMAL(14,2) NULL,
    normalized_data     JSON          NOT NULL,
    raw_data            JSON          NOT NULL,
    approved_by         CHAR(36)      NULL,
    approved_at         TIMESTAMP(6)  NULL,
    published_at        TIMESTAMP(6)  NULL,
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMP(6)  NOT NULL,
    updated_at          TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_captures_org_idempotency (organization_id, idempotency_key),
    INDEX idx_product_captures_org_updated (organization_id, updated_at),
    INDEX idx_product_captures_org_source (organization_id, source_platform, source_product_id),
    CONSTRAINT ck_product_captures_status CHECK (
        status IN ('CAPTURED', 'IMAGE_PROCESSING', 'READY', 'APPROVED', 'PUBLISHED', 'FAILED')
    ),
    CONSTRAINT ck_product_captures_price CHECK (
        (price_min IS NULL OR price_min >= 0)
        AND (price_max IS NULL OR price_max >= 0)
        AND (price_min IS NULL OR price_max IS NULL OR price_max >= price_min)
    ),
    CONSTRAINT fk_product_captures_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_product_captures_creator FOREIGN KEY (created_by) REFERENCES app_users(id),
    CONSTRAINT fk_product_captures_approver FOREIGN KEY (approved_by) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE product_capture_skus (
    id                  CHAR(36)      NOT NULL,
    organization_id     CHAR(36)      NOT NULL,
    capture_id          CHAR(36)      NOT NULL,
    sku_key             VARCHAR(160)  NOT NULL,
    external_sku_id     VARCHAR(160)  NULL,
    seller_sku          VARCHAR(160)  NULL,
    variation_name      VARCHAR(255)  NULL,
    price               DECIMAL(14,2) NULL,
    original_price      DECIMAL(14,2) NULL,
    stock               INT           NULL,
    attributes_json     JSON          NOT NULL,
    source_json         JSON          NOT NULL,
    sort_order          INT           NOT NULL DEFAULT 0,
    created_at          TIMESTAMP(6)  NOT NULL,
    updated_at          TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_capture_skus_key (capture_id, sku_key),
    INDEX idx_product_capture_skus_org_capture (organization_id, capture_id, sort_order),
    CONSTRAINT ck_product_capture_skus_price CHECK (
        (price IS NULL OR price >= 0) AND (original_price IS NULL OR original_price >= 0)
    ),
    CONSTRAINT ck_product_capture_skus_stock CHECK (stock IS NULL OR stock >= 0),
    CONSTRAINT fk_product_capture_skus_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_product_capture_skus_capture FOREIGN KEY (capture_id) REFERENCES product_captures(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE product_capture_images (
    id                  CHAR(36)      NOT NULL,
    organization_id     CHAR(36)      NOT NULL,
    capture_id          CHAR(36)      NOT NULL,
    original_url        TEXT          NOT NULL,
    processed_url       TEXT          NULL,
    status              VARCHAR(24)   NOT NULL,
    role_name           VARCHAR(40)   NULL,
    alt_text            VARCHAR(500)  NULL,
    sort_order          INT           NOT NULL DEFAULT 0,
    mime_type           VARCHAR(120)  NULL,
    error_message       VARCHAR(1000) NULL,
    source_json         JSON          NOT NULL,
    created_at          TIMESTAMP(6)  NOT NULL,
    updated_at          TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_product_capture_images_org_capture (organization_id, capture_id, sort_order),
    CONSTRAINT ck_product_capture_images_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED')
    ),
    CONSTRAINT fk_product_capture_images_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_product_capture_images_capture FOREIGN KEY (capture_id) REFERENCES product_captures(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE product_capture_field_sources (
    id                  CHAR(36)      NOT NULL,
    organization_id     CHAR(36)      NOT NULL,
    capture_id          CHAR(36)      NOT NULL,
    field_name          VARCHAR(160)  NOT NULL,
    source_type         VARCHAR(32)   NOT NULL,
    source_path         VARCHAR(500)  NULL,
    source_value        JSON          NULL,
    confidence          DECIMAL(5,4)  NULL,
    created_at          TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_product_capture_field_sources (organization_id, capture_id, field_name),
    CONSTRAINT ck_product_capture_field_source_type CHECK (
        source_type IN ('DOM', 'REEFAPI', 'TMAPI', 'DERIVED', 'USER')
    ),
    CONSTRAINT ck_product_capture_field_confidence CHECK (
        confidence IS NULL OR (confidence >= 0 AND confidence <= 1)
    ),
    CONSTRAINT fk_product_capture_fields_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_product_capture_fields_capture FOREIGN KEY (capture_id) REFERENCES product_captures(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE product_image_processing_jobs (
    id                  CHAR(36)      NOT NULL,
    organization_id     CHAR(36)      NOT NULL,
    capture_id          CHAR(36)      NOT NULL,
    created_by          CHAR(36)      NOT NULL,
    idempotency_key     VARCHAR(128)  NOT NULL,
    provider            VARCHAR(64)   NOT NULL,
    status              VARCHAR(24)   NOT NULL,
    total_images        INT           NOT NULL DEFAULT 0,
    processed_images    INT           NOT NULL DEFAULT 0,
    request_json        JSON          NOT NULL,
    response_json       JSON          NULL,
    error_message       VARCHAR(2000) NULL,
    created_at          TIMESTAMP(6)  NOT NULL,
    updated_at          TIMESTAMP(6)  NOT NULL,
    completed_at        TIMESTAMP(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_image_jobs_org_idempotency (organization_id, idempotency_key),
    INDEX idx_product_image_jobs_capture (organization_id, capture_id, created_at),
    CONSTRAINT ck_product_image_jobs_status CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')
    ),
    CONSTRAINT fk_product_image_jobs_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_product_image_jobs_capture FOREIGN KEY (capture_id) REFERENCES product_captures(id) ON DELETE CASCADE,
    CONSTRAINT fk_product_image_jobs_creator FOREIGN KEY (created_by) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE product_publish_audits (
    id                  CHAR(36)      NOT NULL,
    organization_id     CHAR(36)      NOT NULL,
    capture_id          CHAR(36)      NOT NULL,
    created_by          CHAR(36)      NOT NULL,
    idempotency_key     VARCHAR(128)  NOT NULL,
    destination_platform VARCHAR(24)  NOT NULL,
    shop_id             VARCHAR(128)  NULL,
    provider            VARCHAR(64)   NOT NULL,
    status              VARCHAR(24)   NOT NULL,
    dry_run             BOOLEAN       NOT NULL DEFAULT TRUE,
    external_id         VARCHAR(255)  NULL,
    request_json        JSON          NOT NULL,
    response_json       JSON          NULL,
    error_message       VARCHAR(2000) NULL,
    created_at          TIMESTAMP(6)  NOT NULL,
    completed_at        TIMESTAMP(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_publish_audits_org_idempotency (organization_id, idempotency_key),
    INDEX idx_product_publish_audits_capture (organization_id, capture_id, created_at),
    CONSTRAINT ck_product_publish_audits_status CHECK (
        status IN ('PENDING', 'PUBLISHING', 'DRY_RUN_SUCCEEDED', 'PUBLISHED', 'FAILED')
    ),
    CONSTRAINT fk_product_publish_audits_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_product_publish_audits_capture FOREIGN KEY (capture_id) REFERENCES product_captures(id),
    CONSTRAINT fk_product_publish_audits_creator FOREIGN KEY (created_by) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE extension_pairing_codes (
    id                  CHAR(36)      NOT NULL,
    organization_id     CHAR(36)      NOT NULL,
    user_id             CHAR(36)      NOT NULL,
    code_hash           CHAR(64)      NOT NULL,
    scopes_json         JSON          NOT NULL,
    expires_at          TIMESTAMP(6)  NOT NULL,
    consumed_at         TIMESTAMP(6)  NULL,
    revoked_at          TIMESTAMP(6)  NULL,
    created_at          TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_extension_pairing_codes_hash (code_hash),
    INDEX idx_extension_pairing_codes_user (organization_id, user_id, expires_at),
    CONSTRAINT fk_extension_pairing_codes_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_extension_pairing_codes_user FOREIGN KEY (user_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE extension_access_tokens (
    id                  CHAR(36)      NOT NULL,
    organization_id     CHAR(36)      NOT NULL,
    user_id             CHAR(36)      NOT NULL,
    token_hash          CHAR(64)      NOT NULL,
    scopes_json         JSON          NOT NULL,
    device_name         VARCHAR(160)  NULL,
    extension_id        VARCHAR(160)  NULL,
    extension_version   VARCHAR(40)   NULL,
    expires_at          TIMESTAMP(6)  NOT NULL,
    last_used_at        TIMESTAMP(6)  NULL,
    revoked_at          TIMESTAMP(6)  NULL,
    created_at          TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_extension_access_tokens_hash (token_hash),
    INDEX idx_extension_access_tokens_user (organization_id, user_id, expires_at),
    CONSTRAINT fk_extension_access_tokens_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_extension_access_tokens_user FOREIGN KEY (user_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO permissions (id, code, name, module, resource_type, description, sort_order) VALUES
    (UUID(), 'product:collect', '采集商品', 'COMMERCE', 'ACTION', '通过浏览器插件采集商品详情并建立草稿', 411),
    (UUID(), 'product:view', '查看商品草稿', 'COMMERCE', 'MENU', '查看本企业采集的商品草稿与处理进度', 412),
    (UUID(), 'product:edit', '编辑商品草稿', 'COMMERCE', 'ACTION', '编辑、整理并审核商品草稿', 413),
    (UUID(), 'product:publish', '发布商品', 'COMMERCE', 'ACTION', '确认并向目标店铺发布已审核商品', 414),
    (UUID(), 'asset:watermark', '处理商品图片', 'ASSET', 'ACTION', '提交商品图片去水印处理任务', 321)
ON DUPLICATE KEY UPDATE
    name = VALUES(name), module = VALUES(module), resource_type = VALUES(resource_type),
    description = VALUES(description), sort_order = VALUES(sort_order);

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN' AND r.built_in = TRUE
  AND p.code IN ('product:collect', 'product:view', 'product:edit', 'product:publish', 'asset:watermark');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
  ON p.code IN ('product:collect', 'product:view', 'product:edit', 'asset:watermark')
WHERE r.code IN ('OPERATOR', 'DESIGNER') AND r.built_in = TRUE;

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
  ON p.code IN ('product:view', 'product:edit')
WHERE r.code = 'REVIEWER' AND r.built_in = TRUE;

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
  ON p.code IN ('product:view', 'product:publish')
WHERE r.code = 'PUBLISHER' AND r.built_in = TRUE;
