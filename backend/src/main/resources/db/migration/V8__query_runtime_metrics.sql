ALTER TABLE query_history
    ADD COLUMN trace_id VARCHAR(128) NULL AFTER query_id,
    ADD COLUMN cpu_ms BIGINT NULL AFTER scanned_bytes,
    ADD COLUMN peak_memory_bytes BIGINT NULL AFTER cpu_ms,
    ADD COLUMN local_scan_bytes BIGINT NULL AFTER peak_memory_bytes,
    ADD COLUMN remote_scan_bytes BIGINT NULL AFTER local_scan_bytes,
    ADD COLUMN cache_write_bytes BIGINT NULL AFTER remote_scan_bytes;

CREATE INDEX idx_query_history_trace_id ON query_history(trace_id);
