ALTER TABLE data_service_definition
  ADD COLUMN revision BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN published_version_id BIGINT NULL;

CREATE TABLE data_service_version (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  service_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  service_code VARCHAR(64) NOT NULL,
  service_name VARCHAR(128) NOT NULL,
  description VARCHAR(512) NULL,
  creator_id BIGINT NOT NULL,
  sql_template TEXT NOT NULL,
  parameter_config JSON NULL,
  catalog_name VARCHAR(128) NOT NULL,
  database_name VARCHAR(64) NOT NULL,
  max_rows INT NOT NULL,
  timeout_seconds INT NOT NULL,
  rate_limit_per_minute INT NOT NULL,
  result_columns_json JSON NULL,
  dependencies_json JSON NULL,
  source_revision BIGINT NOT NULL,
  origin VARCHAR(32) NOT NULL,
  source_version_id BIGINT NULL,
  published_by BIGINT NULL,
  change_summary VARCHAR(512) NULL,
  created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_data_service_version(service_id, version_no)
);

-- Capture the definition that exists at migration time, not an invented historical release.
-- No FK to the mutable definition: version evidence survives an offline service's deletion.
INSERT INTO data_service_version
  (service_id, version_no, service_code, service_name, description, creator_id, sql_template,
   parameter_config, catalog_name, database_name, max_rows, timeout_seconds, rate_limit_per_minute,
   source_revision, origin, change_summary, created_at)
SELECT id, api_version, service_code, service_name, description, creator_id, sql_template,
  parameter_config, catalog_name, database_name, max_rows, timeout_seconds, rate_limit_per_minute,
  0, 'legacy_capture', '升级时捕获的定义；历史发布及结果列契约未认证', CURRENT_TIMESTAMP(6)
FROM data_service_definition WHERE status IN ('published', 'offline');

UPDATE data_service_definition d JOIN data_service_version v ON v.service_id = d.id
SET d.published_version_id = v.id;

ALTER TABLE data_service_invocation_log
  ADD COLUMN version_id BIGINT NULL,
  ADD COLUMN api_version INT NULL,
  ADD COLUMN execution_user_id BIGINT NULL,
  ADD INDEX idx_data_service_log_version(version_id, created_at);
-- Historical invocation versions stay NULL: the old service counter was mutable.
