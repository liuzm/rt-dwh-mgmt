ALTER TABLE quality_rule
    ADD COLUMN check_scope VARCHAR(20) NOT NULL DEFAULT 'full_table',
    ADD COLUMN time_column VARCHAR(100) NULL,
    ADD COLUMN empty_policy VARCHAR(20) NOT NULL DEFAULT 'fail';
ALTER TABLE quality_check_run
    MODIFY COLUMN target_table VARCHAR(255),
    ADD COLUMN layer VARCHAR(20) NULL,
    ADD COLUMN scope_key VARCHAR(128) NOT NULL DEFAULT 'full_table',
    ADD COLUMN window_start DATETIME(6) NULL,
    ADD COLUMN window_end DATETIME(6) NULL,
    ADD COLUMN checked_rows BIGINT NULL,
    ADD COLUMN violation_rows BIGINT NULL,
    ADD COLUMN time_column VARCHAR(100) NULL,
    ADD COLUMN empty_policy VARCHAR(20) NULL,
    ADD INDEX idx_quality_run_scope (rule_id, scope_key, id);
ALTER TABLE quality_alert
    MODIFY COLUMN target_table VARCHAR(255),
    ADD COLUMN layer VARCHAR(20) NULL,
    ADD COLUMN scope_key VARCHAR(128) NOT NULL DEFAULT 'full_table',
    ADD COLUMN window_start DATETIME(6) NULL,
    ADD COLUMN window_end DATETIME(6) NULL;
-- Only infer legacy layers when the historical target still matches the rule.
UPDATE quality_check_run h JOIN quality_rule r ON r.id = h.rule_id AND r.target_table = h.target_table
    SET h.layer = r.layer;
UPDATE quality_alert h JOIN quality_rule r ON r.id = h.rule_id AND r.target_table = h.target_table
    SET h.layer = r.layer;
