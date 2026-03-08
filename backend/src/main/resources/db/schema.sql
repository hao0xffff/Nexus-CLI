CREATE TABLE IF NOT EXISTS work_logs (
    id BIGSERIAL PRIMARY KEY,
    module_name VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    level VARCHAR(16) NOT NULL,
    session_id VARCHAR(128),
    provider VARCHAR(32),
    summary VARCHAR(255) NOT NULL,
    details TEXT,
    status VARCHAR(32),
    duration_ms BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_work_logs_created_at ON work_logs (created_at);
CREATE INDEX IF NOT EXISTS idx_work_logs_module_name ON work_logs (module_name);
CREATE INDEX IF NOT EXISTS idx_work_logs_level ON work_logs (level);
CREATE INDEX IF NOT EXISTS idx_work_logs_session_id ON work_logs (session_id);
