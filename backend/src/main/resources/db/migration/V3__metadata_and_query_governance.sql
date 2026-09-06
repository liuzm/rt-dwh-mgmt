ALTER TABLE dwh_table_meta
  ADD COLUMN owner VARCHAR(64) NULL,
  ADD COLUMN business_domain VARCHAR(64) NULL,
  ADD COLUMN tags JSON NULL,
  ADD COLUMN sensitivity_level VARCHAR(16) NOT NULL DEFAULT 'internal',
  ADD COLUMN lifecycle_status VARCHAR(16) NOT NULL DEFAULT 'active';

CREATE INDEX idx_dwh_owner_domain ON dwh_table_meta (owner, business_domain);
