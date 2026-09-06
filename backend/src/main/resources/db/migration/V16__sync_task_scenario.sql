ALTER TABLE sync_task
  ADD COLUMN scenario_code VARCHAR(64) NULL AFTER task_type;

UPDATE sync_task
SET scenario_code = CASE task_type
  WHEN 'cdc_sync' THEN 'table_realtime_sync'
  WHEN 'etl' THEN 'sql_transform'
  WHEN 'materialized' THEN 'materialized_table'
  ELSE 'custom_task'
END
WHERE scenario_code IS NULL OR scenario_code = '';

ALTER TABLE sync_task
  MODIFY COLUMN scenario_code VARCHAR(64) NOT NULL;

CREATE INDEX idx_sync_task_scenario_code ON sync_task (scenario_code);
