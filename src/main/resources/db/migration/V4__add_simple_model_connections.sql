CREATE TABLE agent_model_configs (
    id CHAR(36) PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    model_id VARCHAR(160) NOT NULL,
    api_format VARCHAR(40) NOT NULL DEFAULT 'OPENAI_COMPATIBLE',
    api_key_ciphertext TEXT NOT NULL,
    key_hint VARCHAR(8) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    default_model BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_agent_model_configs_endpoint
        UNIQUE (organization_id, base_url, model_id),
    CONSTRAINT ck_agent_model_configs_status
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT fk_agent_model_configs_organization
        FOREIGN KEY (organization_id) REFERENCES organizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_agent_model_configs_org_status
    ON agent_model_configs(organization_id, status);

INSERT INTO permissions (
    id, code, name, module, resource_type, description, sort_order
) VALUES
    (UUID(), 'system:model:view', '查看模型接入', 'SYSTEM', 'MENU',
     '查看企业已配置的模型连接', 470),
    (UUID(), 'system:model:manage', '管理模型接入', 'SYSTEM', 'ACTION',
     '新增、修改和停用企业模型连接', 480);

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'ADMIN';
