CREATE TABLE IF NOT EXISTS task_dependency (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  upstream_task_id BIGINT NOT NULL,
  downstream_task_id BIGINT NOT NULL,
  condition_type VARCHAR(20) NOT NULL DEFAULT 'success',
  created_at DATETIME NOT NULL,
  created_by BIGINT NOT NULL,
  CONSTRAINT uk_task_dependency UNIQUE (upstream_task_id, downstream_task_id),
  INDEX idx_task_dependency_downstream (downstream_task_id)
);

CREATE TABLE IF NOT EXISTS task_definition_version (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  change_summary VARCHAR(256) NOT NULL,
  snapshot_json LONGTEXT NOT NULL,
  created_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  CONSTRAINT uk_task_version UNIQUE (task_id, version_no),
  INDEX idx_task_version_time (task_id, created_at)
);

CREATE TABLE IF NOT EXISTS task_run_instance (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  batch_id VARCHAR(64) NOT NULL,
  business_date DATE NOT NULL,
  trigger_type VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,
  parameters_json JSON,
  executor_id VARCHAR(64),
  retry_count INT NOT NULL DEFAULT 0,
  error_message TEXT,
  started_at DATETIME,
  finished_at DATETIME,
  created_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME,
  CONSTRAINT uk_task_run_batch_date UNIQUE (task_id, batch_id, business_date),
  INDEX idx_task_run_status_time (status, created_at),
  INDEX idx_task_run_task_date (task_id, business_date)
);
