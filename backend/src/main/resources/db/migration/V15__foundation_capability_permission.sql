INSERT INTO sys_permission(perm_code, perm_name, resource_type, sort_order)
VALUES ('foundation:view', '查看公共能力治理中心', 'api', 21)
ON DUPLICATE KEY UPDATE perm_name = VALUES(perm_name);

INSERT IGNORE INTO sys_role_permission(role_id, permission_id)
SELECT role.id, permission.id
FROM sys_role role
JOIN sys_permission permission ON permission.perm_code = 'foundation:view'
WHERE role.role_code IN ('ADMIN', 'DEVELOPER', 'VISITOR');
