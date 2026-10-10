-- Narrow the built-in operator role; custom roles and administrators retain their grants.
DELETE rp FROM role_permissions rp
JOIN roles r ON r.id = rp.role_id
JOIN permissions p ON p.id = rp.permission_id
WHERE r.code = 'OPERATOR' AND r.built_in = TRUE
  AND p.code NOT IN ('video:monitor:view', 'video:script:view', 'video:script:manage',
                     'video:task:view', 'video:task:manage');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
ON p.code IN ('video:monitor:view', 'video:script:view', 'video:script:manage',
             'video:task:view', 'video:task:manage')
WHERE r.code = 'OPERATOR' AND r.built_in = TRUE;

UPDATE roles SET description = '仅使用视频工作台：任务监控、视频脚本、AI 视频任务与通用生成'
WHERE code = 'OPERATOR' AND built_in = TRUE;
