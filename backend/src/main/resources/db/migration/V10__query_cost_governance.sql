ALTER TABLE query_history
    ADD COLUMN queue_wait_ms BIGINT NULL AFTER cache_write_bytes,
    ADD COLUMN cost_score DOUBLE NULL AFTER queue_wait_ms,
    ADD COLUMN budget_exceeded BOOLEAN NOT NULL DEFAULT FALSE AFTER cost_score,
    ADD COLUMN budget_reason VARCHAR(512) NULL AFTER budget_exceeded;

CREATE INDEX idx_query_history_budget_created ON query_history(budget_exceeded, created_at);
