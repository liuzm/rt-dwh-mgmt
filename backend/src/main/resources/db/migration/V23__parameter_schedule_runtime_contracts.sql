ALTER TABLE sync_task ADD COLUMN parameter_schema_json JSON NULL;
ALTER TABLE task_schedule ADD COLUMN active_revision_id BIGINT NULL, ADD COLUMN last_error VARCHAR(256) NULL;
ALTER TABLE task_run_instance ADD COLUMN schedule_revision_id BIGINT NULL, ADD COLUMN scheduled_at DATETIME(6) NULL;
CREATE TABLE task_schedule_revision (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL, schedule_id BIGINT NOT NULL, revision_no INT NOT NULL,
  cron_expression VARCHAR(128) NOT NULL, timezone VARCHAR(64) NOT NULL,
  business_date_offset INT NOT NULL, parameters_json JSON NULL, enabled BOOLEAN NOT NULL,
  action VARCHAR(16) NOT NULL, created_by BIGINT NOT NULL, created_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_schedule_revision (task_id, revision_no)
);
-- No foreign keys: independent audit transactions must not lock an in-flight task or instance row.
CREATE TABLE task_access_check (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL, definition_version_id BIGINT NULL, instance_id BIGINT NULL, actor_id BIGINT NULL,
  action VARCHAR(32) NOT NULL, allowed BOOLEAN NOT NULL, reason VARCHAR(256) NOT NULL, checked_at DATETIME(6) NOT NULL,
  KEY idx_access_check_task (task_id, id)
);
