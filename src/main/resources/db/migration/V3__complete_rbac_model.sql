ALTER TABLE permissions
    ADD COLUMN module VARCHAR(60) NOT NULL DEFAULT 'SYSTEM' AFTER name,
    ADD COLUMN sort_order INT NOT NULL DEFAULT 0 AFTER description;

INSERT INTO permissions (id, code, name, module, resource_type, description, sort_order) VALUES
    (UUID(), 'workflow:view', '查看内容任务', 'CONTENT', 'MENU', '查看企业内容生产任务', 110),
    (UUID(), 'workflow:review', '审核内容任务', 'CONTENT', 'ACTION', '审核待发布内容和生成结果', 130),
    (UUID(), 'asset:view', '查看素材中心', 'ASSET', 'MENU', '查看企业图片与视频素材', 210),
    (UUID(), 'publish:view', '查看商品发布', 'COMMERCE', 'MENU', '查看商品与平台发布任务', 310),
    (UUID(), 'system:organization:view', '查看企业资料', 'SYSTEM', 'MENU', '查看当前企业资料', 410),
    (UUID(), 'system:organization:manage', '管理企业资料', 'SYSTEM', 'ACTION', '编辑企业名称、简介与联系方式', 420),
    (UUID(), 'system:user:view', '查看组织人员', 'SYSTEM', 'MENU', '查看部门、人员与账号状态', 430),
    (UUID(), 'system:permission:view', '查看角色权限', 'SYSTEM', 'MENU', '查看角色和权限矩阵', 450)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    module = VALUES(module),
    resource_type = VALUES(resource_type),
    description = VALUES(description),
    sort_order = VALUES(sort_order);

UPDATE permissions SET module = 'DASHBOARD', sort_order = 10 WHERE code = 'dashboard:view';
UPDATE permissions SET module = 'CONTENT', sort_order = 120 WHERE code = 'workflow:manage';
UPDATE permissions SET module = 'ASSET', sort_order = 220 WHERE code = 'asset:manage';
UPDATE permissions SET module = 'COMMERCE', sort_order = 320 WHERE code = 'publish:manage';
UPDATE permissions SET module = 'SYSTEM', sort_order = 440 WHERE code = 'system:user:manage';
UPDATE permissions SET module = 'SYSTEM', sort_order = 460 WHERE code = 'system:role:manage';

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'dashboard:view', 'workflow:view', 'workflow:manage',
    'asset:view', 'asset:manage'
)
WHERE r.code = 'OPERATOR';

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'dashboard:view', 'workflow:view', 'asset:view', 'asset:manage'
)
WHERE r.code = 'DESIGNER';

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'dashboard:view', 'workflow:view', 'workflow:review',
    'asset:view'
)
WHERE r.code = 'REVIEWER';

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'dashboard:view', 'workflow:view', 'publish:view', 'publish:manage'
)
WHERE r.code = 'PUBLISHER';

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'ADMIN';
