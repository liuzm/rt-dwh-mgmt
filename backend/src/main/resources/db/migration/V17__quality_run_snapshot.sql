ALTER TABLE quality_check_run
  ADD COLUMN rule_type VARCHAR(50) NULL AFTER rule_name,
  ADD COLUMN target_table VARCHAR(100) NULL AFTER rule_type,
  ADD COLUMN target_column VARCHAR(100) NULL AFTER target_table;
