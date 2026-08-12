UPDATE app_users u
LEFT JOIN app_users d
    ON d.organization_id = u.organization_id
    AND d.username = '13800000000'
    AND d.id <> u.id
SET u.phone = '13800000000',
    u.username = '13800000000'
WHERE u.username = 'admin'
  AND (u.phone IS NULL OR TRIM(u.phone) = '')
  AND d.id IS NULL;

UPDATE app_users u
LEFT JOIN app_users d
    ON d.organization_id = u.organization_id
    AND d.username = u.phone
    AND d.id <> u.id
SET u.username = u.phone
WHERE u.phone IS NOT NULL
  AND TRIM(u.phone) <> ''
  AND d.id IS NULL;

UPDATE app_users SET employee_no = NULL;
