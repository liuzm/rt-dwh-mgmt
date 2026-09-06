-- Persist ownership independently of the guard transaction. A coordinator whose
-- database connection was lost cannot overwrite a later coordinator's evidence.
ALTER TABLE table_maintenance_log ADD COLUMN coordination_token VARCHAR(36);
