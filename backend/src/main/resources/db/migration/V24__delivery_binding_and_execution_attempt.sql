ALTER TABLE task_run_instance
  ADD COLUMN active_attempt_id BIGINT NULL,
  ADD COLUMN attempt_count INT NOT NULL DEFAULT 0,
  ADD COLUMN delivery_status VARCHAR(16) NOT NULL DEFAULT 'unknown',
  ADD COLUMN delivery_error VARCHAR(256) NULL,
  ADD COLUMN window_start DATE NULL,
  ADD COLUMN window_end DATE NULL,
  ADD COLUMN binding_policy VARCHAR(24) NULL,
  ADD KEY idx_run_delivery (status, delivery_status, id);
-- Historical success is not evidence of verified data delivery. Do not backfill it as available.
ALTER TABLE task_dependency ADD COLUMN output_dataset_id BIGINT NULL;
ALTER TABLE dataset_production
  ADD COLUMN window_start DATE NULL,
  ADD COLUMN window_end DATE NULL,
  ADD COLUMN definition_version_id BIGINT NULL,
  ADD COLUMN attempt_id BIGINT NULL,
  ADD COLUMN delivery_key VARCHAR(96) NULL,
  ADD COLUMN quality_batch_id VARCHAR(64) NULL,
  ADD COLUMN reason VARCHAR(256) NULL,
  ADD COLUMN checked_at DATETIME(6) NULL,
  ADD UNIQUE KEY uk_production_delivery_key (delivery_key),
  ADD KEY idx_production_binding (output_dataset_id, definition_version_id, window_start, window_end, status);
-- Nullable keys preserve all old rows, including any historical duplicates.
CREATE TABLE task_run_attempt (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, instance_id BIGINT NOT NULL, attempt_no INT NOT NULL,
  executor_id VARCHAR(64) NOT NULL, external_job_id VARCHAR(64), status VARCHAR(24) NOT NULL,
  error_message VARCHAR(512), started_at DATETIME(6) NOT NULL, submitted_at DATETIME(6), finished_at DATETIME(6),
  UNIQUE KEY uk_run_attempt (instance_id, attempt_no),
  CONSTRAINT fk_attempt_instance FOREIGN KEY (instance_id) REFERENCES task_run_instance(id) ON DELETE CASCADE
);
CREATE TABLE task_run_dependency_binding (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, instance_id BIGINT NOT NULL, dependency_id BIGINT NOT NULL,
  upstream_task_id BIGINT NOT NULL, upstream_version_id BIGINT NOT NULL, upstream_instance_id BIGINT,
  output_dataset_id BIGINT, production_id BIGINT, condition_type VARCHAR(20) NOT NULL,
  binding_policy VARCHAR(24) NOT NULL, window_start DATE NOT NULL, window_end DATE NOT NULL, bound_at DATETIME(6),
  UNIQUE KEY uk_run_dependency_binding (instance_id, dependency_id),
  CONSTRAINT fk_binding_instance FOREIGN KEY (instance_id) REFERENCES task_run_instance(id) ON DELETE CASCADE
);
CREATE TABLE dataset_production_check (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, production_id BIGINT NOT NULL, quality_batch_id VARCHAR(64),
  status VARCHAR(16) NOT NULL, reason VARCHAR(256), checked_at DATETIME(6) NOT NULL,
  KEY idx_production_check (production_id, id),
  CONSTRAINT fk_delivery_check_production FOREIGN KEY (production_id) REFERENCES dataset_production(id) ON DELETE CASCADE
);
