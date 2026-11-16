CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS se_frms_ai_audit_log (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    endpoint VARCHAR(50) NOT NULL,
    model VARCHAR(100) NOT NULL,
    request_payload TEXT,
    response_payload TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_audit_log_endpoint
    ON se_frms_ai_audit_log(endpoint);

CREATE INDEX IF NOT EXISTS idx_ai_audit_log_created_at
    ON se_frms_ai_audit_log(created_at);
