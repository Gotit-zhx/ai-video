CREATE TABLE automation_capabilities (
    code VARCHAR(80) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    version VARCHAR(40) NOT NULL,
    command_type VARCHAR(64) NOT NULL,
    supported_os VARCHAR(40) NOT NULL,
    entry_point VARCHAR(240) NOT NULL,
    input_schema JSON NOT NULL,
    package_path VARCHAR(1000),
    checksum_sha256 CHAR(64),
    source_type VARCHAR(24) NOT NULL,
    runner_type VARCHAR(24) NOT NULL,
    conversion_report JSON,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_automation_capabilities_status
        CHECK (status IN ('DRAFT', 'ACTIVE', 'DISABLED')),
    CONSTRAINT ck_automation_capabilities_source
        CHECK (source_type IN ('BUNDLED', 'UPLOADED')),
    CONSTRAINT ck_automation_capabilities_runner
        CHECK (runner_type IN ('PROCESS_LOCAL', 'AGENT_LOCAL', 'MANUAL_ADAPTER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO automation_capabilities (
    code, name, description, version, command_type, supported_os,
    entry_point, input_schema, package_path, checksum_sha256,
    source_type, runner_type, conversion_report, status, sort_order
) VALUES (
    'YUE_BILL_EXPORT',
    '月账单导出',
    '按日期批量导出 TikTok、Shopee、Lazada 店铺收入、广告和余额账单',
    '1.0.0',
    'YUE_BILL_EXPORT',
    'WINDOWS',
    'run.ps1',
    JSON_OBJECT(
        'fields', JSON_ARRAY(
            JSON_OBJECT('name', 'workbookPath', 'label', '店铺工作簿', 'type', 'localFile', 'required', TRUE, 'accept', '.xlsx'),
            JSON_OBJECT('name', 'startDate', 'label', '开始日期', 'type', 'date', 'required', TRUE),
            JSON_OBJECT('name', 'endDate', 'label', '结束日期', 'type', 'date', 'required', TRUE),
            JSON_OBJECT('name', 'outputRoot', 'label', '输出目录', 'type', 'localDirectory', 'required', TRUE),
            JSON_OBJECT('name', 'maxConcurrency', 'label', '并发店铺数', 'type', 'number', 'required', TRUE, 'default', 3, 'min', 1, 'max', 5)
        )
    ),
    'bundled-skills/yue-bill-parallel-10',
    NULL,
    'BUNDLED',
    'PROCESS_LOCAL',
    JSON_OBJECT('converted', TRUE, 'warnings', JSON_ARRAY()),
    'ACTIVE',
    10
);

CREATE TABLE user_automation_grants (
    organization_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    automation_code VARCHAR(80) NOT NULL,
    granted_by CHAR(36),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, automation_code),
    CONSTRAINT fk_user_automation_grants_organization
        FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_user_automation_grants_user
        FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_automation_grants_capability
        FOREIGN KEY (automation_code) REFERENCES automation_capabilities(code),
    CONSTRAINT fk_user_automation_grants_granter
        FOREIGN KEY (granted_by) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE automation_agents
    ADD COLUMN bound_user_id CHAR(36) NULL AFTER organization_id,
    ADD CONSTRAINT uk_automation_agents_bound_user UNIQUE (bound_user_id),
    ADD CONSTRAINT fk_automation_agents_bound_user
        FOREIGN KEY (bound_user_id) REFERENCES app_users(id) ON DELETE SET NULL;

ALTER TABLE automation_commands
    ADD COLUMN automation_code VARCHAR(80) NULL AFTER command_type,
    ADD COLUMN progress_percent INT NOT NULL DEFAULT 0 AFTER status,
    ADD COLUMN progress_message VARCHAR(500) NULL AFTER progress_percent,
    ADD CONSTRAINT fk_automation_commands_capability
        FOREIGN KEY (automation_code) REFERENCES automation_capabilities(code);

INSERT INTO permissions (
    id, code, name, module, resource_type, description, sort_order
) VALUES
    (UUID(), 'automation:view', '使用自动化中心', 'AUTOMATION', 'MENU', '查看并运行已授权的本机自动化', 360),
    (UUID(), 'system:automation:manage', '配置人员自动化', 'SYSTEM', 'ACTION', '配置人员可执行的自动化与绑定设备', 470);

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'ADMIN'
  AND p.code IN ('automation:view', 'system:automation:manage');

INSERT IGNORE INTO user_automation_grants (
    organization_id, user_id, automation_code, granted_by
)
SELECT u.organization_id, u.id, 'YUE_BILL_EXPORT', u.id
FROM app_users u
JOIN user_roles ur ON ur.user_id = u.id
JOIN roles r ON r.id = ur.role_id
WHERE r.code = 'ADMIN';
