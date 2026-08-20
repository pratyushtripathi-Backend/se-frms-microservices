CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS se_frms_decision (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    transaction_id UUID NOT NULL,
    scoring_id UUID NOT NULL,
    total_risk_score INTEGER NOT NULL CHECK (total_risk_score >= 0),
    final_decision VARCHAR(20) NOT NULL CHECK (final_decision IN ('ALLOW', 'REVIEW', 'BLOCK')),
    decision_reason VARCHAR(500),
    status BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_transaction_decision UNIQUE (transaction_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_decision_transaction
    ON se_frms_decision(transaction_id);

CREATE INDEX IF NOT EXISTS idx_decision_scoring
    ON se_frms_decision(scoring_id);

CREATE INDEX IF NOT EXISTS idx_decision_score
    ON se_frms_decision(total_risk_score);

CREATE INDEX IF NOT EXISTS idx_decision_final
    ON se_frms_decision(final_decision);

CREATE INDEX IF NOT EXISTS idx_decision_status
    ON se_frms_decision(status);

CREATE INDEX IF NOT EXISTS idx_decision_created_at
    ON se_frms_decision(created_at);
