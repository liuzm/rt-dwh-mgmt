CREATE TABLE data_service_definition (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, service_code VARCHAR(64) NOT NULL, service_name VARCHAR(128) NOT NULL,
  description VARCHAR(512) NULL, creator_id BIGINT NOT NULL, sql_template TEXT NOT NULL, parameter_config JSON NULL,
  catalog_name VARCHAR(128) NOT NULL, database_name VARCHAR(64) NOT NULL,
  max_rows INT NOT NULL DEFAULT 1000, timeout_seconds INT NOT NULL DEFAULT 30,
  rate_limit_per_minute INT NOT NULL DEFAULT 60, status VARCHAR(16) NOT NULL DEFAULT 'draft',
  api_version INT NOT NULL DEFAULT 1, published_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_data_service_code(service_code)
);
CREATE TABLE data_service_app (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, app_name VARCHAR(128) NOT NULL, app_key VARCHAR(64) NOT NULL,
  secret_hash VARCHAR(128) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, expires_at DATETIME NULL,
  created_by BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_data_service_app_key(app_key)
);
CREATE TABLE data_service_grant (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, app_id BIGINT NOT NULL, service_id BIGINT NOT NULL,
  created_by BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_data_service_grant(app_id, service_id),
  CONSTRAINT fk_data_service_grant_app FOREIGN KEY(app_id) REFERENCES data_service_app(id) ON DELETE CASCADE,
  CONSTRAINT fk_data_service_grant_service FOREIGN KEY(service_id) REFERENCES data_service_definition(id) ON DELETE CASCADE
);
CREATE TABLE data_service_invocation_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, service_id BIGINT NULL, app_id BIGINT NULL,
  service_code VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL, http_status INT NOT NULL,
  row_count INT NULL, duration_ms BIGINT NULL, client_ip VARCHAR(64) NULL, error_message TEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_data_service_log_time(service_id, created_at), INDEX idx_data_service_log_app(app_id, created_at)
);

INSERT INTO sys_permission(perm_code, perm_name, resource_type, sort_order)
VALUES ('data-service:view','查看数据服务','api',19),('data-service:manage','管理数据服务','api',20)
ON DUPLICATE KEY UPDATE perm_name=VALUES(perm_name);
INSERT IGNORE INTO sys_role_permission(role_id, permission_id)
SELECT r.id,p.id FROM sys_role r JOIN sys_permission p ON p.perm_code IN ('data-service:view','data-service:manage') WHERE r.role_code IN ('ADMIN','DEVELOPER');
