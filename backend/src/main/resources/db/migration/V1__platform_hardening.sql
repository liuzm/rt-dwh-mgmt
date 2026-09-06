CREATE TABLE IF NOT EXISTS sys_permission (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  perm_code VARCHAR(64) NOT NULL UNIQUE,
  perm_name VARCHAR(64) NOT NULL,
  resource_type ENUM('menu','button','api') NOT NULL,
  parent_id BIGINT,
  sort_order INT DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS quality_check_run (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  batch_id VARCHAR(64) NOT NULL,
  rule_id BIGINT NOT NULL,
  rule_name VARCHAR(100) NOT NULL,
  trigger_type VARCHAR(20) NOT NULL,
  engine VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,
  check_sql TEXT NOT NULL,
  actual_value DOUBLE,
  threshold_value DOUBLE,
  duration_ms BIGINT,
  error_message TEXT,
  started_at DATETIME NOT NULL,
  finished_at DATETIME,
  INDEX idx_quality_run_batch (batch_id),
  INDEX idx_quality_run_rule_time (rule_id, started_at)
);

CREATE TABLE IF NOT EXISTS operation_audit (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT,
  username VARCHAR(64) NOT NULL,
  http_method VARCHAR(16) NOT NULL,
  request_path VARCHAR(256) NOT NULL,
  action VARCHAR(64) NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  resource_id VARCHAR(128),
  client_ip VARCHAR(64),
  success BOOLEAN NOT NULL,
  response_status INT,
  duration_ms BIGINT,
  error_message TEXT,
  created_at DATETIME NOT NULL,
  INDEX idx_audit_user_time (username, created_at),
  INDEX idx_audit_resource_time (resource_type, created_at)
);

INSERT INTO sys_permission (perm_code, perm_name, resource_type, sort_order) VALUES
  ('datasource:view', '查看数据源', 'api', 11),
  ('datasource:manage', '管理数据源', 'api', 12),
  ('quality:view', '查看数据质量', 'api', 13),
  ('quality:manage', '管理数据质量', 'api', 14),
  ('lineage:view', '查看数据血缘', 'api', 15),
  ('alert:view', '查看告警', 'api', 16),
  ('settings:view', '查看系统状态', 'api', 17),
  ('audit:view', '查看操作审计', 'api', 18)
ON DUPLICATE KEY UPDATE perm_name = VALUES(perm_name), resource_type = VALUES(resource_type);
