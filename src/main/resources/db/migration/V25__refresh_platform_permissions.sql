INSERT INTO permissions (
    id, code, name, module, resource_type, description, sort_order
) VALUES
    (UUID(), 'video:script:view', '查看视频脚本', 'VIDEO', 'MENU', '查看视频拆解任务、分镜和脚本版本', 210),
    (UUID(), 'video:script:manage', '管理视频脚本', 'VIDEO', 'ACTION', '上传、拆解、编辑、重试和删除视频脚本', 220),
    (UUID(), 'video:task:view', '查看 AI 视频任务', 'VIDEO', 'MENU', '查看商品素材、生成进度、提示词和视频结果', 230),
    (UUID(), 'video:task:manage', '生成 AI 视频', 'VIDEO', 'ACTION', '创建任务、上传素材、生成图片与视频并执行重试', 240),
    (UUID(), 'automation:execute', '执行自动化', 'AUTOMATION', 'ACTION', '上传执行附件并启动、暂停、恢复或中断已授权自动化', 520),
    (UUID(), 'assistant:view', '查看助手测试', 'ASSISTANT', 'MENU', '查看助手、模型、Pi Runtime 与本机执行器联调状态', 610),
    (UUID(), 'assistant:use', '使用助手测试', 'ASSISTANT', 'ACTION', '发送助手指令并读取本次联调产生的结果', 620),
    (UUID(), 'system:automation:view', '查看自动化配置', 'SYSTEM', 'MENU', '查看自动化 Skill、执行环境、人员授权和设备状态', 770)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    module = VALUES(module),
    resource_type = VALUES(resource_type),
    description = VALUES(description),
    sort_order = VALUES(sort_order);

UPDATE permissions SET module = 'DASHBOARD', sort_order = 10 WHERE code = 'dashboard:view';
UPDATE permissions SET module = 'CONTENT', sort_order = 110 WHERE code = 'workflow:view';
UPDATE permissions SET module = 'CONTENT', sort_order = 120 WHERE code = 'workflow:manage';
UPDATE permissions SET module = 'CONTENT', sort_order = 130 WHERE code = 'workflow:review';
UPDATE permissions SET module = 'ASSET', sort_order = 310 WHERE code = 'asset:view';
UPDATE permissions SET module = 'ASSET', sort_order = 320 WHERE code = 'asset:manage';
UPDATE permissions SET module = 'COMMERCE', sort_order = 410 WHERE code = 'publish:view';
UPDATE permissions SET module = 'COMMERCE', sort_order = 420 WHERE code = 'publish:manage';
UPDATE permissions SET module = 'AUTOMATION', sort_order = 510 WHERE code = 'automation:view';
UPDATE permissions SET module = 'SYSTEM', sort_order = 710 WHERE code = 'system:organization:view';
UPDATE permissions SET module = 'SYSTEM', sort_order = 720 WHERE code = 'system:organization:manage';
UPDATE permissions SET module = 'SYSTEM', sort_order = 730 WHERE code = 'system:user:view';
UPDATE permissions SET module = 'SYSTEM', sort_order = 740 WHERE code = 'system:user:manage';
UPDATE permissions SET module = 'SYSTEM', sort_order = 750 WHERE code = 'system:permission:view';
UPDATE permissions SET module = 'SYSTEM', sort_order = 760 WHERE code = 'system:role:manage';
UPDATE permissions SET module = 'SYSTEM', sort_order = 780 WHERE code = 'system:automation:manage';
UPDATE permissions SET module = 'SYSTEM', sort_order = 790 WHERE code = 'system:model:view';
UPDATE permissions SET module = 'SYSTEM', sort_order = 800 WHERE code = 'system:model:manage';

UPDATE roles
SET description = CASE code
    WHEN 'ADMIN' THEN '管理企业配置、人员、模型、自动化与全部业务数据'
    WHEN 'OPERATOR' THEN '管理内容工作流、视频脚本、素材和 AI 视频生产'
    WHEN 'DESIGNER' THEN '处理商品素材、视频脚本、分镜和视觉生成任务'
    WHEN 'REVIEWER' THEN '查看视频生产过程并审核内容与生成结果'
    WHEN 'PUBLISHER' THEN '查看视频成片并管理店铺发布与促销任务'
    ELSE description
END
WHERE built_in = TRUE
  AND code IN ('ADMIN', 'OPERATOR', 'DESIGNER', 'REVIEWER', 'PUBLISHER');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'ADMIN' AND r.built_in = TRUE;

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'video:script:view', 'video:script:manage',
    'video:task:view', 'video:task:manage',
    'automation:view', 'automation:execute'
)
WHERE r.code = 'OPERATOR' AND r.built_in = TRUE;

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'video:script:view', 'video:script:manage',
    'video:task:view', 'video:task:manage'
)
WHERE r.code = 'DESIGNER' AND r.built_in = TRUE;

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('video:script:view', 'video:task:view')
WHERE r.code = 'REVIEWER' AND r.built_in = TRUE;

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'video:task:view'
WHERE r.code = 'PUBLISHER' AND r.built_in = TRUE;
