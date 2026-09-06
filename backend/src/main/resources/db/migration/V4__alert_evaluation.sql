ALTER TABLE alert_record
  ADD COLUMN rule_id BIGINT NULL AFTER id,
  ADD COLUMN dedup_key VARCHAR(160) NULL AFTER rule_id,
  ADD COLUMN recovered_at DATETIME NULL AFTER resolved_at,
  ADD COLUMN last_evaluated_at DATETIME NULL AFTER recovered_at,
  ADD COLUMN notification_status VARCHAR(20) NULL AFTER last_evaluated_at;

CREATE INDEX idx_alert_rule_open ON alert_record (rule_id, resolved);
CREATE INDEX idx_alert_dedup ON alert_record (rule_id, dedup_key, resolved);
