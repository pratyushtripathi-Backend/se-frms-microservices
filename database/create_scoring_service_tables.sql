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

CREATE TABLE IF NOT EXISTS se_frms_matched_rule (
    id UUID PRIMARY KEY,
    scoring_id UUID NOT NULL,
    rule_id INTEGER NOT NULL,
    rule_code VARCHAR(255) NOT NULL,
    rule_name VARCHAR(255) NOT NULL,
    rule_score INTEGER NOT NULL,
    calculated_score INTEGER NOT NULL,
    status BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(100) NOT NULL,
    created_date TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_matched_rule_scoring
        FOREIGN KEY (scoring_id)
        REFERENCES se_frms_scoring (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_matched_rule_rule_score_non_negative CHECK (rule_score >= 0),
    CONSTRAINT ck_matched_rule_calculated_score_non_negative CHECK (calculated_score >= 0)
);

CREATE INDEX IF NOT EXISTS idx_scoring_transaction_id
    ON se_frms_scoring (transaction_id);

CREATE INDEX IF NOT EXISTS idx_matched_rule_scoring_id
    ON se_frms_matched_rule (scoring_id);

CREATE INDEX IF NOT EXISTS idx_matched_rule_rule_id
    ON se_frms_matched_rule (rule_id);
