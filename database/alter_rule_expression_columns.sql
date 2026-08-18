ALTER TABLE se_frms_fraud_rule
    ADD COLUMN IF NOT EXISTS rule_expression TEXT;

ALTER TABLE se_frms_rule_cache
    ADD COLUMN IF NOT EXISTS rule_expression TEXT;
