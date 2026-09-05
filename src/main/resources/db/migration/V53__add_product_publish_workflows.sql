CREATE TABLE product_publish_workflows (
    id                       CHAR(36)      NOT NULL,
    organization_id          CHAR(36)      NOT NULL,
    capture_id               CHAR(36)      NOT NULL,
    created_by               CHAR(36)      NOT NULL,
    idempotency_key          VARCHAR(128)  NOT NULL,
    request_fingerprint      CHAR(64)      NOT NULL,
    publication_target_key   CHAR(64)      NULL,
    destination_platform     VARCHAR(24)   NOT NULL,
    shop_id                  CHAR(36)      NULL,
    capture_version          BIGINT        NOT NULL,
    expected_capture_version BIGINT        NOT NULL,
    request_json             JSON          NOT NULL,
    status                   VARCHAR(24)   NOT NULL,
    current_step             VARCHAR(24)   NOT NULL,
    image_job_id             CHAR(36)      NULL,
    publish_audit_id         CHAR(36)      NULL,
    publication_attempted    BOOLEAN       NOT NULL DEFAULT FALSE,
    attempt_count            INT           NOT NULL DEFAULT 0,
    retry_count              INT           NOT NULL DEFAULT 0,
    next_attempt_at          DATETIME(6)   NOT NULL,
    locked_by                VARCHAR(80)   NULL,
    locked_until             DATETIME(6)   NULL,
    error_message            VARCHAR(2000) NULL,
    created_at               DATETIME(6)   NOT NULL,
    updated_at               DATETIME(6)   NOT NULL,
    completed_at             DATETIME(6)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_publish_workflows_org_key (organization_id, idempotency_key),
    UNIQUE KEY uk_product_publish_workflows_target (publication_target_key),
    INDEX idx_product_publish_workflows_claim (status, next_attempt_at, locked_until),
    INDEX idx_product_publish_workflows_capture
        (organization_id, capture_id, created_at),
    CONSTRAINT ck_product_publish_workflows_status CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')
    ),
    CONSTRAINT ck_product_publish_workflows_step CHECK (
        current_step IN ('IMAGES', 'APPROVE', 'PUBLISH', 'COMPLETED')
    ),
    CONSTRAINT fk_product_publish_workflows_org
        FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_product_publish_workflows_capture
        FOREIGN KEY (capture_id) REFERENCES product_captures(id) ON DELETE CASCADE,
    CONSTRAINT fk_product_publish_workflows_creator
        FOREIGN KEY (created_by) REFERENCES app_users(id),
    CONSTRAINT fk_product_publish_workflows_shop
        FOREIGN KEY (shop_id) REFERENCES shops(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
