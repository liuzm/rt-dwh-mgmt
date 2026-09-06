CREATE TABLE role_data_scope (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    role_id BIGINT NOT NULL,
    catalog_pattern VARCHAR(128) NOT NULL,
    database_pattern VARCHAR(128) NOT NULL,
    table_pattern VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_role_data_scope_role FOREIGN KEY (role_id) REFERENCES sys_role(id) ON DELETE CASCADE,
    UNIQUE KEY uk_role_data_scope_pattern (role_id, catalog_pattern, database_pattern, table_pattern),
    INDEX idx_role_data_scope_role (role_id)
);

INSERT INTO role_data_scope(role_id, catalog_pattern, database_pattern, table_pattern)
SELECT id, '*', '*', '*' FROM sys_role WHERE role_code IN ('DEVELOPER', 'VISITOR');
