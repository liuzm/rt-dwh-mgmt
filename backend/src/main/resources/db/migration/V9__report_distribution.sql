ALTER TABLE report_run
    ADD COLUMN attempt_count INT NULL AFTER row_count,
    ADD COLUMN delivery_status VARCHAR(20) NULL AFTER error_message,
    ADD COLUMN delivery_error TEXT NULL AFTER delivery_status;
