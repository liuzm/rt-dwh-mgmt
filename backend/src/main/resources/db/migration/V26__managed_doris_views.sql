-- Catalog is part of physical addressing; retain logical asset UUIDs.
ALTER TABLE dwh_table_meta DROP INDEX uk_db_table,
 ADD UNIQUE KEY uk_catalog_db_table (catalog_name, paimon_db, paimon_table);
CREATE TABLE managed_view (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 table_meta_id BIGINT NOT NULL UNIQUE,
 draft_sql LONGTEXT NOT NULL,
 published_version_id BIGINT NULL,
 pending_version_id BIGINT NULL,
 operation_state VARCHAR(16) NOT NULL DEFAULT 'idle',
 last_error TEXT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT fk_managed_view_asset FOREIGN KEY (table_meta_id) REFERENCES dwh_table_meta(id)
);
CREATE TABLE managed_view_version (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 view_id BIGINT NOT NULL,
 version_no INT NOT NULL,
 sql_content LONGTEXT NOT NULL,
 dependencies_json JSON NOT NULL,
 columns_json JSON NOT NULL,
 engine_definition LONGTEXT NULL,
 status VARCHAR(16) NOT NULL,
 created_by BIGINT NOT NULL,
 created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_managed_view_version (view_id, version_no),
 CONSTRAINT fk_view_version_view FOREIGN KEY (view_id) REFERENCES managed_view(id)
);
