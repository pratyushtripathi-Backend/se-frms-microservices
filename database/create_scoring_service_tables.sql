CREATE TABLE IF NOT EXISTS se_frms_scoring (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL,
    total_risk_score INTEGER NOT NULL DEFAULT 0,
    status BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(100) NOT NULL,
    created_date TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_scoring_total_risk_score_non_negative CHECK (total_risk_score >= 0)
);

CREATE INDEX IF NOT EXISTS idx_scoring_transaction_id
    ON se_frms_scoring (transaction_id);

CREATE TABLE IF NOT EXISTS se_frms_matched_rule (
    id UUID PRIMARY KEY,
    scoring_id UUID NOT NULL UNIQUE,
    rule_code JSONB NOT NULL DEFAULT '[]'::jsonb,
    rule_name JSONB NOT NULL DEFAULT '[]'::jsonb,
    rule_expression JSONB NOT NULL DEFAULT '[]'::jsonb,
    rule_score JSONB NOT NULL DEFAULT '[]'::jsonb,
    calculated_score JSONB NOT NULL DEFAULT '[]'::jsonb,
    status BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(100) NOT NULL,
    created_date TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_matched_rule_scoring
        FOREIGN KEY (scoring_id)
        REFERENCES se_frms_scoring (id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_matched_rule_scoring_id
    ON se_frms_matched_rule (scoring_id);
