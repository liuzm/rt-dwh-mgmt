ALTER TABLE report_template
    ADD COLUMN schedule_enabled BOOLEAN NOT NULL DEFAULT FALSE AFTER schedule_config,
    ADD COLUMN next_run_at DATETIME NULL AFTER schedule_enabled,
    ADD COLUMN last_run_at DATETIME NULL AFTER next_run_at;

CREATE INDEX idx_report_schedule_due
    ON report_template (is_published, schedule_enabled, next_run_at);

CREATE TABLE report_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_id BIGINT NOT NULL,
    trigger_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    scheduled_at DATETIME,
    started_at DATETIME NOT NULL,
    finished_at DATETIME,
    duration_ms BIGINT,
    row_count INT,
    result_json LONGTEXT,
    error_message TEXT,
    executed_by BIGINT NOT NULL,
    INDEX idx_report_run_report_time (report_id, started_at),
    INDEX idx_report_run_status_time (status, started_at)
);
