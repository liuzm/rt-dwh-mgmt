ALTER TABLE sync_task
    ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT 'continuous' AFTER scenario_code,
    ADD COLUMN definition_status VARCHAR(16) NOT NULL DEFAULT 'draft' AFTER execution_mode,
    ADD COLUMN published_version_id BIGINT NULL AFTER definition_status,
    MODIFY COLUMN source_config_id BIGINT NULL,
    MODIFY COLUMN target_config_id BIGINT NULL;

UPDATE sync_task
SET execution_mode = CASE
    WHEN scenario_code = 'scheduled_sql_output' THEN 'scheduled'
    ELSE 'continuous'
END;

UPDATE sync_task task
SET definition_status = CASE
        WHEN EXISTS (SELECT 1 FROM task_definition_version version WHERE version.task_id = task.id)
            THEN 'published'
        ELSE 'draft'
    END,
    published_version_id = (
        SELECT version.id
        FROM task_definition_version version
        WHERE version.task_id = task.id
        ORDER BY version.version_no DESC
        LIMIT 1
    );

ALTER TABLE task_run_instance
    ADD COLUMN definition_version_id BIGINT NULL AFTER task_id;

UPDATE task_run_instance instance
SET definition_version_id = (
    SELECT version.id
    FROM task_definition_version version
    WHERE version.task_id = instance.task_id
    ORDER BY version.version_no DESC
    LIMIT 1
);

CREATE INDEX idx_task_run_definition_version
    ON task_run_instance (definition_version_id);
