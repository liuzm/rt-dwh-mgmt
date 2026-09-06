ALTER TABLE table_maintenance_log
  ADD COLUMN revision BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN contract_origin VARCHAR(32) NOT NULL DEFAULT 'legacy_unbound',
  ADD COLUMN asset_id VARCHAR(64),
  ADD COLUMN catalog_name VARCHAR(128),
  ADD COLUMN database_name VARCHAR(128),
  ADD COLUMN table_name VARCHAR(128),
  ADD COLUMN requested_by BIGINT,
  ADD COLUMN gateway_url VARCHAR(1024),
  ADD COLUMN flink_url VARCHAR(1024),
  ADD COLUMN environment_json JSON,
  ADD COLUMN correlation_name VARCHAR(128),
  ADD COLUMN observed_at DATETIME(6),
  ADD COLUMN observed_state VARCHAR(32),
  ADD COLUMN cleanup_status VARCHAR(24) NOT NULL DEFAULT 'untracked',
  ADD COLUMN cleanup_attempts INT NOT NULL DEFAULT 0,
  ADD COLUMN cleanup_next_at DATETIME(6),
  ADD COLUMN cleanup_error VARCHAR(512),
  ADD COLUMN cleaned_at DATETIME(6),
  ADD INDEX idx_maintenance_cleanup(cleanup_status, cleanup_next_at);

-- Old addresses, ownership and historical targets cannot be inferred from current settings.
-- Preserve old handles and status; legacy active records remain blocked for evidence review.
CREATE TABLE maintenance_coordination_lock (
  table_meta_id BIGINT PRIMARY KEY
);

CREATE TABLE maintenance_recovery_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  maintenance_id BIGINT NOT NULL,
  actor_id BIGINT,
  action VARCHAR(40) NOT NULL,
  reason VARCHAR(1000),
  evidence_json JSON NOT NULL,
  created_at DATETIME(6) NOT NULL,
  INDEX idx_maintenance_event(maintenance_id, id)
);
