CREATE TABLE task_schedule (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, task_id BIGINT NOT NULL,
  cron_expression VARCHAR(128) NOT NULL, timezone VARCHAR(64) NOT NULL,
  business_date_offset INT NOT NULL DEFAULT -1, parameters_json JSON NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE, next_run_at TIMESTAMP(6) NULL, last_run_at TIMESTAMP(6) NULL,
  created_by BIGINT NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_task_schedule_task(task_id), INDEX idx_task_schedule_due(enabled, next_run_at),
  CONSTRAINT fk_task_schedule_task FOREIGN KEY(task_id) REFERENCES sync_task(id) ON DELETE CASCADE
);

CREATE TABLE task_output_dataset (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, task_id BIGINT NOT NULL,
  catalog_name VARCHAR(128) NOT NULL, database_name VARCHAR(64) NOT NULL, table_name VARCHAR(128) NOT NULL,
  layer VARCHAR(8) NOT NULL, owner VARCHAR(64) NULL, business_desc VARCHAR(512) NULL,
  sla_minutes INT NOT NULL DEFAULT 1440, quality_gate_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  last_produced_at DATETIME NULL, last_instance_id BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_task_output_name(task_id, catalog_name, database_name, table_name),
  INDEX idx_task_output_task(task_id),
  CONSTRAINT fk_task_output_task FOREIGN KEY(task_id) REFERENCES sync_task(id) ON DELETE CASCADE
);

CREATE TABLE dataset_production (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, output_dataset_id BIGINT NOT NULL,
  task_id BIGINT NOT NULL, instance_id BIGINT NOT NULL, business_date DATE NOT NULL,
  status VARCHAR(16) NOT NULL, produced_at DATETIME NOT NULL,
  UNIQUE KEY uk_dataset_production_instance(output_dataset_id, instance_id),
  INDEX idx_dataset_production_output_time(output_dataset_id, produced_at),
  CONSTRAINT fk_dataset_production_output FOREIGN KEY(output_dataset_id) REFERENCES task_output_dataset(id) ON DELETE CASCADE
);
