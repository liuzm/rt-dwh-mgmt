UPDATE quality_rule SET enabled = TRUE WHERE enabled IS NULL;
UPDATE alert_rule SET enabled = TRUE WHERE enabled IS NULL;
UPDATE quality_alert SET resolved = FALSE WHERE resolved IS NULL;
UPDATE alert_record SET resolved = FALSE WHERE resolved IS NULL;

ALTER TABLE quality_rule
  MODIFY COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE alert_rule
  MODIFY COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE quality_alert
  MODIFY COLUMN resolved BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE alert_record
  MODIFY COLUMN resolved BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE quality_check_run
  ADD COLUMN rule_version BIGINT NULL AFTER target_column;

ALTER TABLE quality_rule
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER enabled;

ALTER TABLE alert_rule
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER enabled;

ALTER TABLE quality_alert
  ADD COLUMN resolution_reason VARCHAR(20) NULL AFTER resolved_at,
  MODIFY COLUMN message TEXT NULL;

ALTER TABLE alert_record
  ADD COLUMN resolution_reason VARCHAR(20) NULL AFTER resolved_at,
  ADD COLUMN recovery_notification_status VARCHAR(20) NULL AFTER notification_status,
  ADD COLUMN delivery_kind VARCHAR(20) NULL AFTER recovery_notification_status,
  ADD COLUMN delivery_claim_token VARCHAR(64) NULL AFTER delivery_kind,
  ADD COLUMN delivery_claimed_at DATETIME(6) NULL AFTER delivery_claim_token,
  ADD COLUMN delivery_attempt_count INT NOT NULL DEFAULT 0 AFTER delivery_claimed_at,
  ADD COLUMN delivery_next_attempt_at DATETIME(6) NULL AFTER delivery_attempt_count,
  ADD COLUMN delivery_last_error TEXT NULL AFTER delivery_next_attempt_at,
  MODIFY COLUMN message TEXT NULL;

CREATE INDEX idx_quality_alert_resolved_time
  ON quality_alert (resolved, triggered_at);

CREATE INDEX idx_quality_alert_rule_open_time
  ON quality_alert (rule_id, resolved, triggered_at);

CREATE INDEX idx_quality_run_status_time
  ON quality_check_run (status, started_at);

CREATE INDEX idx_quality_run_started_at
  ON quality_check_run (started_at);
