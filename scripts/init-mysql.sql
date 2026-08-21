-- RT-DWH Management Platform - Database Initialization
-- MySQL 5.7+
-- Note: With JPA ddl-auto: update, Hibernate auto-creates/migrates tables.
-- This script serves as a reference and for manual setup scenarios.

CREATE DATABASE IF NOT EXISTS rtdwh_mgmt
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

-- Paimon JDBC Catalog metastore. Paimon creates its catalog tables lazily,
-- but the containing database must exist before the first connection.
-- Paimon 2.0 creates paimon_table_properties with a four-column VARCHAR(255)
-- primary key. utf8mb4 can require 4080 index bytes and exceeds InnoDB's
-- 3072-byte limit; utf8mb3 requires at most 3060.
CREATE DATABASE IF NOT EXISTS rtdwh_paimon_meta
  CHARACTER SET utf8mb3
  COLLATE utf8mb3_unicode_ci;

-- The official MySQL image creates MYSQL_USER before executing init scripts.
-- Keep the default deployment user able to initialize Paimon catalog tables.
GRANT ALL PRIVILEGES ON rtdwh_paimon_meta.* TO 'rtdwh_admin'@'%';
FLUSH PRIVILEGES;

USE rtdwh_mgmt;

-- Latest persisted system dependency health snapshot
CREATE TABLE IF NOT EXISTS sys_health_status (
  status_key VARCHAR(32) PRIMARY KEY,
  overall_status VARCHAR(20) NOT NULL,
  payload_json LONGTEXT NOT NULL,
  checked_at DATETIME NOT NULL,
  duration_ms BIGINT NOT NULL DEFAULT 0,
  check_source VARCHAR(20) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- SysUser
CREATE TABLE IF NOT EXISTS sys_user (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(256) NOT NULL,
  real_name VARCHAR(64),
  email VARCHAR(128),
  phone VARCHAR(20),
  avatar_url VARCHAR(256),
  status ENUM('active','disabled') NOT NULL DEFAULT 'active',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- SysRole
CREATE TABLE IF NOT EXISTS sys_role (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_code VARCHAR(32) NOT NULL UNIQUE,
  role_name VARCHAR(64) NOT NULL,
  description VARCHAR(256),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- SysPermission
CREATE TABLE IF NOT EXISTS sys_permission (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  perm_code VARCHAR(64) NOT NULL UNIQUE,
  perm_name VARCHAR(64) NOT NULL,
  resource_type ENUM('menu','button','api') NOT NULL,
  parent_id BIGINT,
  sort_order INT DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- SysUserRole (N:N)
CREATE TABLE IF NOT EXISTS sys_user_role (
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, role_id)
);

-- SysRolePermission (N:N)
CREATE TABLE IF NOT EXISTS sys_role_permission (
  role_id BIGINT NOT NULL,
  permission_id BIGINT NOT NULL,
  PRIMARY KEY (role_id, permission_id)
);

-- DatasourceConfig
CREATE TABLE IF NOT EXISTS datasource_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  creator_id BIGINT NOT NULL,
  config_name VARCHAR(128) NOT NULL,
  db_type ENUM('mysql','postgresql','paimon') NOT NULL,
  host VARCHAR(128) NOT NULL,
  port INT NOT NULL,
  `database` VARCHAR(128) NOT NULL,
  username VARCHAR(64) NOT NULL,
  password_encrypted VARCHAR(256) NOT NULL,
  extra_params JSON,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- SyncTask
CREATE TABLE IF NOT EXISTS sync_task (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  creator_id BIGINT NOT NULL,
  task_name VARCHAR(128) NOT NULL,
  description TEXT,
  task_type ENUM('cdc_sync','etl','materialized') NOT NULL,
  source_config_id BIGINT NOT NULL,
  target_config_id BIGINT NOT NULL,
  flink_sql TEXT,
  sync_strategy ENUM('full_then_incremental','incremental_only') NOT NULL,
  table_mappings JSON,
  status ENUM('draft','submitting','running','saving_point','paused','failed','finished') NOT NULL DEFAULT 'draft',
  flink_job_id VARCHAR(64),
  flink_jar_id VARCHAR(64),
  savepoint_trigger_id VARCHAR(64),
  checkpoint_info JSON,
  current_lag_ms BIGINT,
  throughput_qps DOUBLE,
  last_error_msg TEXT,
  parallelism INT DEFAULT 1,
  checkpoint_interval_ms BIGINT,
  checkpoint_count BIGINT DEFAULT 0,
  submitted_at DATETIME DEFAULT NULL,
  last_checkpoint_time DATETIME DEFAULT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  FOREIGN KEY (creator_id) REFERENCES sys_user(id),
  FOREIGN KEY (source_config_id) REFERENCES datasource_config(id),
  FOREIGN KEY (target_config_id) REFERENCES datasource_config(id)
);

-- DwhTableMeta
CREATE TABLE IF NOT EXISTS dwh_table_meta (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  paimon_db VARCHAR(64) NOT NULL,
  paimon_table VARCHAR(128) NOT NULL,
  layer ENUM('ods','dwd','dws','ads') NOT NULL,
  business_desc TEXT,
  schema_json JSON,
  partition_keys VARCHAR(256),
  primary_keys VARCHAR(256),
  snapshot_count INT,
  latest_snapshot_id BIGINT,
  latest_commit_time TIMESTAMP,
  file_count INT,
  total_size_bytes BIGINT,
  record_count BIGINT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_db_table (paimon_db, paimon_table)
);

-- DwhColumnMeta
CREATE TABLE IF NOT EXISTS dwh_column_meta (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  table_meta_id BIGINT NOT NULL,
  column_name VARCHAR(128) NOT NULL,
  column_type VARCHAR(64) NOT NULL,
  business_comment VARCHAR(512),
  is_pk BOOLEAN DEFAULT FALSE,
  is_nullable BOOLEAN DEFAULT TRUE,
  default_value VARCHAR(128),
  source_column VARCHAR(128),
  sort_order INT DEFAULT 0,
  FOREIGN KEY (table_meta_id) REFERENCES dwh_table_meta(id)
);

-- DwhSchemaHistory
CREATE TABLE IF NOT EXISTS dwh_schema_history (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  table_meta_id BIGINT NOT NULL,
  change_type ENUM('add_column','drop_column','rename_column','alter_type') NOT NULL,
  before_schema JSON,
  after_schema JSON,
  change_detail TEXT,
  detected_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (table_meta_id) REFERENCES dwh_table_meta(id)
);

-- DwhDataLineage
CREATE TABLE IF NOT EXISTS dwh_data_lineage (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  source_table_id BIGINT NOT NULL,
  target_table_id BIGINT NOT NULL,
  sync_task_id BIGINT,
  lineage_type ENUM('cdc_sync','etl_transform','materialized') NOT NULL,
  transform_logic TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (source_table_id) REFERENCES dwh_table_meta(id),
  FOREIGN KEY (target_table_id) REFERENCES dwh_table_meta(id),
  FOREIGN KEY (sync_task_id) REFERENCES sync_task(id)
);

-- TableMaintenanceLog (updated to match entity)
CREATE TABLE IF NOT EXISTS table_maintenance_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  table_meta_id BIGINT NOT NULL,
  operation ENUM('compact','expire_snapshots','orphan_cleanup') NOT NULL,
  trigger_type ENUM('manual','scheduled') NOT NULL,
  status ENUM('running','success','failed','pending') NOT NULL DEFAULT 'running',
  before_metrics JSON,
  after_metrics JSON,
  duration_ms BIGINT,
  error_msg TEXT,
  operation_id VARCHAR(64),
  sql_content TEXT,
  started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  finished_at TIMESTAMP,
  FOREIGN KEY (table_meta_id) REFERENCES dwh_table_meta(id)
);

-- QualityRule (updated: added threshold, layer, expression columns)
CREATE TABLE IF NOT EXISTS quality_rule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_name VARCHAR(100) NOT NULL,
  rule_type VARCHAR(50) NOT NULL,
  layer VARCHAR(20),
  target_table VARCHAR(100),
  target_column VARCHAR(100),
  threshold DOUBLE NOT NULL,
  expression VARCHAR(500),
  enabled BOOLEAN DEFAULT TRUE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- QualityAlert (updated: added actual_value, threshold_value, target_table, target_column, rule_id)
CREATE TABLE IF NOT EXISTS quality_alert (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_type VARCHAR(50) NOT NULL,
  target_table VARCHAR(100),
  target_column VARCHAR(100),
  actual_value DOUBLE,
  threshold_value DOUBLE,
  message VARCHAR(500),
  level VARCHAR(20),
  rule_id BIGINT,
  resolved BOOLEAN DEFAULT FALSE,
  resolved_at TIMESTAMP,
  triggered_at TIMESTAMP
);

-- AlertRule (matches entity)
CREATE TABLE IF NOT EXISTS alert_rule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_name VARCHAR(100) NOT NULL,
  rule_type VARCHAR(50) NOT NULL,
  expression TEXT,
  enabled BOOLEAN DEFAULT TRUE,
  notify_channel VARCHAR(20),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- AlertRecord (updated: removed FK to alert_config, matches entity)
CREATE TABLE IF NOT EXISTS alert_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_type VARCHAR(50) NOT NULL,
  message VARCHAR(500),
  level VARCHAR(20),
  resolved BOOLEAN DEFAULT FALSE,
  resolved_at TIMESTAMP,
  triggered_at TIMESTAMP
);

-- ReportTemplate
CREATE TABLE IF NOT EXISTS report_template (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  creator_id BIGINT NOT NULL,
  report_name VARCHAR(128) NOT NULL,
  report_type ENUM('line','bar','pie','table','mixed') NOT NULL,
  sql_query TEXT NOT NULL,
  chart_config JSON,
  filter_config JSON,
  schedule_config JSON,
  is_published BOOLEAN DEFAULT FALSE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  FOREIGN KEY (creator_id) REFERENCES sys_user(id)
);

-- QueryHistory
CREATE TABLE IF NOT EXISTS query_history (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  sql_text TEXT NOT NULL,
  query_type ENUM('adhoc','report') NOT NULL,
  result_row_count INT,
  duration_ms BIGINT,
  status ENUM('running','success','failed','cancelled') NOT NULL,
  error_msg TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES sys_user(id)
);

-- SavedQuery: user-owned SQL library
CREATE TABLE IF NOT EXISTS saved_query (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  sql_text TEXT NOT NULL,
  description VARCHAR(512),
  tags VARCHAR(256),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_saved_query_user_name (user_id, name),
  FOREIGN KEY (user_id) REFERENCES sys_user(id)
);

-- Quartz tables (auto-created by Spring Boot when initialize-schema: always)

-- Insert initial permissions
INSERT INTO sys_permission (perm_code, perm_name, resource_type, sort_order) VALUES
  ('task:create', '创建同步任务', 'api', 1),
  ('task:manage', '管理同步任务', 'api', 2),
  ('task:view', '查看同步任务', 'api', 3),
  ('dwh:manage', '管理数仓元数据', 'api', 4),
  ('dwh:view', '查看数仓表', 'api', 5),
  ('query:adhoc', '即席查询', 'api', 6),
  ('report:create', '创建报表', 'api', 7),
  ('report:view', '查看报表', 'api', 8),
  ('alert:manage', '管理告警', 'api', 9),
  ('settings:manage', '系统设置', 'api', 10);
