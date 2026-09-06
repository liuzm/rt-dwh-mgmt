ALTER TABLE table_maintenance_log
    MODIFY COLUMN status ENUM('running','success','failed','pending','unknown','timed_out') NOT NULL DEFAULT 'running',
    ADD COLUMN session_id VARCHAR(64),
    ADD COLUMN flink_job_id VARCHAR(64),
    ADD COLUMN execution_phase VARCHAR(20);

-- Old running entries have no durable Gateway session and cannot be certified as successful.
UPDATE table_maintenance_log SET status = 'unknown', error_msg = '历史维护缺少会话信息，需人工核对执行结果'
WHERE status = 'running';
