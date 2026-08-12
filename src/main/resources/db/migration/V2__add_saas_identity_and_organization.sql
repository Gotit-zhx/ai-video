ALTER TABLE organizations
    ADD COLUMN code VARCHAR(40) NULL AFTER id,
    ADD COLUMN logo_url VARCHAR(512) NULL AFTER status,
    ADD COLUMN description VARCHAR(1000) NULL AFTER logo_url,
    ADD COLUMN contact_name VARCHAR(80) NULL AFTER description,
    ADD COLUMN contact_phone VARCHAR(40) NULL AFTER contact_name,
    ADD COLUMN plan_code VARCHAR(40) NOT NULL DEFAULT 'STARTER' AFTER contact_phone;

UPDATE organizations
SET code = CONCAT('ORG_', UPPER(SUBSTRING(REPLACE(id, '-', ''), 1, 8)))
WHERE code IS NULL;

ALTER TABLE organizations
    MODIFY COLUMN code VARCHAR(40) NOT NULL,
    ADD CONSTRAINT uk_organizations_code UNIQUE (code);

CREATE TABLE departments (
    id CHAR(36) PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    parent_id CHAR(36),
    name VARCHAR(120) NOT NULL,
    code VARCHAR(60) NOT NULL,
    leader_user_id CHAR(36),
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_departments_org_code UNIQUE (organization_id, code),
    CONSTRAINT ck_departments_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT fk_departments_organization
        FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_departments_parent
        FOREIGN KEY (parent_id) REFERENCES departments(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE app_users
    ADD COLUMN department_id CHAR(36) NULL AFTER organization_id,
    ADD COLUMN employee_no VARCHAR(60) NULL AFTER display_name,
    ADD COLUMN email VARCHAR(160) NULL AFTER employee_no,
    ADD COLUMN phone VARCHAR(40) NULL AFTER email,
    ADD COLUMN job_title VARCHAR(100) NULL AFTER phone,
    ADD COLUMN last_login_at DATETIME(6) NULL AFTER status,
    ADD CONSTRAINT uk_app_users_org_employee UNIQUE (organization_id, employee_no),
    ADD CONSTRAINT fk_app_users_department
        FOREIGN KEY (department_id) REFERENCES departments(id);

CREATE TABLE roles (
    id CHAR(36) PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    code VARCHAR(60) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    data_scope VARCHAR(32) NOT NULL DEFAULT 'SELF',
    built_in BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_roles_org_code UNIQUE (organization_id, code),
    CONSTRAINT ck_roles_data_scope CHECK (
        data_scope IN ('ALL', 'DEPARTMENT', 'DEPARTMENT_AND_CHILDREN', 'SELF')
    ),
    CONSTRAINT ck_roles_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT fk_roles_organization
        FOREIGN KEY (organization_id) REFERENCES organizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE permissions (
    id CHAR(36) PRIMARY KEY,
    code VARCHAR(120) NOT NULL,
    name VARCHAR(120) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    description VARCHAR(500),
    CONSTRAINT uk_permissions_code UNIQUE (code),
    CONSTRAINT ck_permissions_resource_type CHECK (
        resource_type IN ('MENU', 'API', 'ACTION')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_roles (
    user_id CHAR(36) NOT NULL,
    role_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user
        FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role
        FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE role_permissions (
    role_id CHAR(36) NOT NULL,
    permission_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role
        FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permissions_permission
        FOREIGN KEY (permission_id) REFERENCES permissions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_shop_scopes (
    user_id CHAR(36) NOT NULL,
    shop_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, shop_id),
    CONSTRAINT fk_user_shop_scopes_user
        FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_shop_scopes_shop
        FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE login_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    organization_id CHAR(36),
    user_id CHAR(36),
    username VARCHAR(80) NOT NULL,
    success BOOLEAN NOT NULL,
    failure_reason VARCHAR(255),
    ip_address VARCHAR(64),
    user_agent VARCHAR(500),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_login_logs_organization
        FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_login_logs_user
        FOREIGN KEY (user_id) REFERENCES app_users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_departments_org_parent ON departments(organization_id, parent_id);
CREATE INDEX idx_app_users_department ON app_users(department_id);
CREATE INDEX idx_roles_organization ON roles(organization_id);
CREATE INDEX idx_login_logs_user_created ON login_logs(user_id, created_at);

INSERT INTO permissions (id, code, name, resource_type, description) VALUES
    (UUID(), 'dashboard:view', '查看工作台', 'MENU', '查看企业运营工作台'),
    (UUID(), 'workflow:manage', '管理内容任务', 'ACTION', '创建和管理 AI 内容工作流'),
    (UUID(), 'asset:manage', '管理素材', 'ACTION', '管理企业图片与视频素材'),
    (UUID(), 'publish:manage', '管理发布', 'ACTION', '管理商品和内容发布任务'),
    (UUID(), 'system:user:manage', '管理人员', 'ACTION', '管理企业部门和人员账号'),
    (UUID(), 'system:role:manage', '管理角色', 'ACTION', '配置角色及权限');
