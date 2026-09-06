ALTER TABLE task_run_instance
    ADD COLUMN external_job_id VARCHAR(64) NULL AFTER executor_id,
    ADD COLUMN heartbeat_at DATETIME NULL AFTER finished_at,
    ADD COLUMN lease_expires_at DATETIME NULL AFTER heartbeat_at,
    ADD COLUMN next_retry_at DATETIME NULL AFTER lease_expires_at;

CREATE INDEX idx_task_run_retry_time
    ON task_run_instance (status, next_retry_at);

CREATE INDEX idx_task_run_lease
    ON task_run_instance (status, lease_expires_at);
