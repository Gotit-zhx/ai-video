CREATE TABLE organizations (
    id CHAR(36) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_organizations_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE app_users (
    id CHAR(36) PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    username VARCHAR(80) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    password_hash VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_app_users_org_username UNIQUE (organization_id, username),
    CONSTRAINT ck_app_users_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    CONSTRAINT fk_app_users_organization
        FOREIGN KEY (organization_id) REFERENCES organizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE shops (
    id CHAR(36) PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    platform VARCHAR(32) NOT NULL,
    external_shop_id VARCHAR(128),
    name VARCHAR(160) NOT NULL,
    region VARCHAR(32),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_AUTH',
    credentials_secret_ref VARCHAR(255),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_shops_platform_external UNIQUE (platform, external_shop_id),
    CONSTRAINT ck_shops_status CHECK (
        status IN ('PENDING_AUTH', 'ACTIVE', 'TOKEN_EXPIRED', 'DISABLED')
    ),
    CONSTRAINT fk_shops_organization
        FOREIGN KEY (organization_id) REFERENCES organizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE products (
    id CHAR(36) PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    spu_code VARCHAR(80) NOT NULL,
    name VARCHAR(255) NOT NULL,
    category_code VARCHAR(128),
    brand_name VARCHAR(128),
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    attributes JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_products_org_spu UNIQUE (organization_id, spu_code),
    CONSTRAINT ck_products_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT fk_products_organization
        FOREIGN KEY (organization_id) REFERENCES organizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE product_skus (
    id CHAR(36) PRIMARY KEY,
    product_id CHAR(36) NOT NULL,
    sku_code VARCHAR(80) NOT NULL,
    attributes JSON NOT NULL,
    price DECIMAL(14, 2),
    inventory INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_product_skus_product_code UNIQUE (product_id, sku_code),
    CONSTRAINT ck_product_skus_price CHECK (price IS NULL OR price >= 0),
    CONSTRAINT ck_product_skus_inventory CHECK (inventory >= 0),
    CONSTRAINT fk_product_skus_product
        FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE assets (
    id CHAR(36) PRIMARY KEY,
    organization_id CHAR(36),
    product_id CHAR(36),
    type VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    original_url TEXT,
    mime_type VARCHAR(128),
    file_size BIGINT,
    sha256 VARCHAR(64),
    width INT,
    height INT,
    duration_ms BIGINT,
    metadata JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_assets_type CHECK (
        type IN ('IMAGE', 'VIDEO', 'AUDIO', 'DOCUMENT', 'OTHER')
    ),
    CONSTRAINT ck_assets_source_type CHECK (
        source_type IN ('UPLOAD', 'GENERATED', 'COLLECTED', 'PLATFORM', 'SYSTEM')
    ),
    CONSTRAINT ck_assets_file_size CHECK (file_size IS NULL OR file_size >= 0),
    CONSTRAINT fk_assets_organization
        FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_assets_product
        FOREIGN KEY (product_id) REFERENCES products(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE workflow_jobs (
    id CHAR(36) PRIMARY KEY,
    job_no VARCHAR(64) NOT NULL,
    organization_id CHAR(36),
    product_id CHAR(36),
    type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    title VARCHAR(200) NOT NULL,
    input_payload JSON NOT NULL,
    output_payload JSON,
    budget_limit DECIMAL(14, 4),
    estimated_cost DECIMAL(14, 4) NOT NULL DEFAULT 0,
    actual_cost DECIMAL(14, 4) NOT NULL DEFAULT 0,
    error_message VARCHAR(2000),
    created_by CHAR(36),
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_workflow_jobs_job_no UNIQUE (job_no),
    CONSTRAINT ck_workflow_jobs_status CHECK (
        status IN (
            'DRAFT', 'QUEUED', 'RUNNING', 'REVIEW_REQUIRED',
            'APPROVED', 'COMPLETED', 'FAILED', 'CANCELLED'
        )
    ),
    CONSTRAINT ck_workflow_jobs_cost CHECK (
        estimated_cost >= 0 AND actual_cost >= 0
        AND (budget_limit IS NULL OR budget_limit >= 0)
    ),
    CONSTRAINT fk_workflow_jobs_organization
        FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_workflow_jobs_product
        FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT fk_workflow_jobs_creator
        FOREIGN KEY (created_by) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE workflow_steps (
    id CHAR(36) PRIMARY KEY,
    workflow_job_id CHAR(36) NOT NULL,
    step_key VARCHAR(80) NOT NULL,
    skill_name VARCHAR(160),
    skill_version VARCHAR(64),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt INT NOT NULL DEFAULT 0,
    input_payload JSON NOT NULL,
    output_payload JSON,
    external_task_id VARCHAR(255),
    error_message VARCHAR(2000),
    started_at DATETIME(6),
    finished_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_workflow_steps_job_key UNIQUE (workflow_job_id, step_key),
    CONSTRAINT ck_workflow_steps_status CHECK (
        status IN (
            'PENDING', 'SUBMITTED', 'RUNNING', 'REVIEW_REQUIRED',
            'SUCCEEDED', 'FAILED', 'TIMEOUT', 'CANCELLED'
        )
    ),
    CONSTRAINT ck_workflow_steps_attempt CHECK (attempt >= 0),
    CONSTRAINT fk_workflow_steps_job
        FOREIGN KEY (workflow_job_id) REFERENCES workflow_jobs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE model_call_records (
    id CHAR(36) PRIMARY KEY,
    workflow_job_id CHAR(36),
    workflow_step_id CHAR(36),
    provider VARCHAR(80) NOT NULL,
    model VARCHAR(160) NOT NULL,
    request_id VARCHAR(255),
    task_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    estimated_cost DECIMAL(14, 4) NOT NULL DEFAULT 0,
    actual_cost DECIMAL(14, 4) NOT NULL DEFAULT 0,
    latency_ms BIGINT,
    request_summary JSON NOT NULL,
    response_summary JSON,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6),
    CONSTRAINT ck_model_call_records_status CHECK (
        status IN ('SUBMITTED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMEOUT', 'CANCELLED')
    ),
    CONSTRAINT fk_model_calls_job
        FOREIGN KEY (workflow_job_id) REFERENCES workflow_jobs(id),
    CONSTRAINT fk_model_calls_step
        FOREIGN KEY (workflow_step_id) REFERENCES workflow_steps(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE publish_tasks (
    id CHAR(36) PRIMARY KEY,
    workflow_job_id CHAR(36),
    shop_id CHAR(36) NOT NULL,
    product_id CHAR(36),
    asset_id CHAR(36),
    publish_type VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    request_payload JSON NOT NULL,
    response_payload JSON,
    external_id VARCHAR(255),
    scheduled_at DATETIME(6),
    published_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_publish_tasks_shop_idempotency UNIQUE (shop_id, idempotency_key),
    CONSTRAINT ck_publish_tasks_type CHECK (
        publish_type IN ('CONTENT', 'PRODUCT', 'PROMOTION')
    ),
    CONSTRAINT ck_publish_tasks_status CHECK (
        status IN (
            'DRAFT', 'REVIEW_REQUIRED', 'APPROVED', 'QUEUED',
            'PUBLISHING', 'PUBLISHED', 'FAILED', 'CANCELLED'
        )
    ),
    CONSTRAINT fk_publish_tasks_job
        FOREIGN KEY (workflow_job_id) REFERENCES workflow_jobs(id),
    CONSTRAINT fk_publish_tasks_shop
        FOREIGN KEY (shop_id) REFERENCES shops(id),
    CONSTRAINT fk_publish_tasks_product
        FOREIGN KEY (product_id) REFERENCES products(id),
    CONSTRAINT fk_publish_tasks_asset
        FOREIGN KEY (asset_id) REFERENCES assets(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE audit_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    organization_id CHAR(36),
    operator_id CHAR(36),
    action VARCHAR(120) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    resource_id VARCHAR(128),
    request_id VARCHAR(128),
    detail JSON NOT NULL,
    ip_address VARCHAR(64),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_audit_logs_organization
        FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_audit_logs_operator
        FOREIGN KEY (operator_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_app_users_organization ON app_users(organization_id);
CREATE INDEX idx_shops_organization ON shops(organization_id);
CREATE INDEX idx_products_organization_status ON products(organization_id, status);
CREATE INDEX idx_assets_product ON assets(product_id);
CREATE INDEX idx_assets_sha256 ON assets(sha256);
CREATE INDEX idx_workflow_jobs_status_created ON workflow_jobs(status, created_at);
CREATE INDEX idx_workflow_jobs_organization ON workflow_jobs(organization_id);
CREATE INDEX idx_workflow_steps_job_status ON workflow_steps(workflow_job_id, status);
CREATE INDEX idx_model_calls_job ON model_call_records(workflow_job_id);
CREATE INDEX idx_publish_tasks_status_scheduled ON publish_tasks(status, scheduled_at);
CREATE INDEX idx_audit_logs_resource ON audit_logs(resource_type, resource_id);
