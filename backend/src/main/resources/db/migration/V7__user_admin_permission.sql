INSERT INTO sys_permission (perm_code, perm_name, resource_type, sort_order)
VALUES ('user:manage', '管理用户与角色', 'api', 19)
ON DUPLICATE KEY UPDATE perm_name = VALUES(perm_name), resource_type = VALUES(resource_type);
