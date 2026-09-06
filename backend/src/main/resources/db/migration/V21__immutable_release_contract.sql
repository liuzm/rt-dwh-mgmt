ALTER TABLE task_definition_version
    ADD COLUMN contract_json LONGTEXT,
    ADD COLUMN contract_hash VARCHAR(64);
-- No backfill: historical runtime dependencies and quality rules cannot be reconstructed reliably.

ALTER TABLE task_run_instance ADD COLUMN access_checked_at DATETIME;
