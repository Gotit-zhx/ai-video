INSERT INTO permissions (
    id, code, name, module, resource_type, description, sort_order
) VALUES (
    UUID(), 'video:monitor:view', '查看 AI 任务监控台', 'VIDEO', 'MENU',
    '查询 AI 视频任务的生图资产、视频请求、线路重试、费用和错误明细', 250
)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    module = VALUES(module),
    resource_type = VALUES(resource_type),
    description = VALUES(description),
    sort_order = VALUES(sort_order);

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'video:monitor:view'
WHERE r.code = 'ADMIN' AND r.built_in = TRUE;
