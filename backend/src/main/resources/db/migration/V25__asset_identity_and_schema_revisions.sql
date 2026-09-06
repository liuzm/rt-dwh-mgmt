ALTER TABLE dwh_table_meta
  ADD COLUMN asset_id VARCHAR(36) NULL,
  ADD COLUMN catalog_name VARCHAR(128) NULL,
  ADD COLUMN asset_type VARCHAR(32) NOT NULL DEFAULT 'paimon_table',
  ADD COLUMN discovery_status VARCHAR(16) NOT NULL DEFAULT 'unverified',
  ADD COLUMN schema_status VARCHAR(16) NOT NULL DEFAULT 'unknown',
  ADD COLUMN last_seen_at DATETIME(6) NULL,
  ADD COLUMN schema_observed_at DATETIME(6) NULL;
UPDATE dwh_table_meta SET asset_id = UUID() WHERE asset_id IS NULL;
ALTER TABLE dwh_table_meta MODIFY asset_id VARCHAR(36) NOT NULL,
  ADD UNIQUE KEY uk_asset_id (asset_id);
ALTER TABLE dwh_column_meta ADD COLUMN engine_field_id BIGINT NULL,
  MODIFY column_type TEXT NOT NULL;
CREATE TABLE asset_schema_revision (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  table_meta_id BIGINT NOT NULL, revision_no INT NOT NULL,
  severity VARCHAR(16) NOT NULL, evidence_source VARCHAR(32) NOT NULL,
  fingerprint VARCHAR(64) NOT NULL, before_schema JSON, after_schema JSON NOT NULL,
  changes_json JSON NOT NULL, observed_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_asset_schema_revision (table_meta_id, revision_no),
  CONSTRAINT fk_asset_schema_table FOREIGN KEY (table_meta_id) REFERENCES dwh_table_meta(id) ON DELETE CASCADE
);
-- Existing schemas remain unverified until observed; no fictional change history is generated.
ALTER TABLE task_output_dataset ADD COLUMN asset_id VARCHAR(36) NULL;
ALTER TABLE dataset_production ADD COLUMN asset_id VARCHAR(36) NULL;
