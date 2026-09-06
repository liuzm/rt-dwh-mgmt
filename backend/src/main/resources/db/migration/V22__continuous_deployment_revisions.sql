ALTER TABLE sync_task ADD COLUMN active_deployment_id BIGINT;
CREATE TABLE task_deployment_revision (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  definition_version_id BIGINT NOT NULL,
  requested_by BIGINT NOT NULL,
  action_type VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL,
  flink_job_id VARCHAR(64),
  contract_hash VARCHAR(64),
  restore_path VARCHAR(2048),
  error_message VARCHAR(512),
  desired_parallelism INT,
  created_at DATETIME NOT NULL,
  observed_at DATETIME,
  INDEX idx_deployment_task (task_id,id),
  INDEX idx_deployment_status (status,id)
);
