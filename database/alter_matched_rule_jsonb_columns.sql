-- Run once on the existing scoring database, before deploying the code change.
-- It changes se_frms_matched_rule to one row per scoring_id with five JSONB arrays.

ALTER TABLE se_frms_matched_rule
    ADD COLUMN IF NOT EXISTS rule_expression TEXT;

CREATE TEMP TABLE matched_rule_jsonb_backup AS
SELECT
    (array_agg(id ORDER BY created_date, id))[1] AS id,
    scoring_id,
    jsonb_agg(rule_code ORDER BY created_date, id) AS rule_code,
    jsonb_agg(rule_name ORDER BY created_date, id) AS rule_name,
    jsonb_agg(rule_expression ORDER BY created_date, id) AS rule_expression,
    jsonb_agg(rule_score ORDER BY created_date, id) AS rule_score,
    jsonb_agg(calculated_score ORDER BY created_date, id) AS calculated_score,
    bool_and(status) AS status,
    min(created_by) AS created_by,
    min(created_date) AS created_date,
    max(updated_at) AS updated_at
FROM se_frms_matched_rule
GROUP BY scoring_id;

DELETE FROM se_frms_matched_rule;

-- These old numeric constraints cannot remain after rule_score and
-- calculated_score become JSONB arrays.
ALTER TABLE se_frms_matched_rule
    DROP CONSTRAINT IF EXISTS ck_matched_rule_rule_score_non_negative;
ALTER TABLE se_frms_matched_rule
    DROP CONSTRAINT IF EXISTS ck_matched_rule_calculated_score_non_negative;

ALTER TABLE se_frms_matched_rule DROP COLUMN IF EXISTS rule_id;
ALTER TABLE se_frms_matched_rule ALTER COLUMN rule_code DROP NOT NULL;
ALTER TABLE se_frms_matched_rule ALTER COLUMN rule_name DROP NOT NULL;
ALTER TABLE se_frms_matched_rule ALTER COLUMN rule_score DROP NOT NULL;
ALTER TABLE se_frms_matched_rule ALTER COLUMN calculated_score DROP NOT NULL;
ALTER TABLE se_frms_matched_rule ALTER COLUMN rule_code TYPE JSONB USING '[]'::jsonb;
ALTER TABLE se_frms_matched_rule ALTER COLUMN rule_name TYPE JSONB USING '[]'::jsonb;
ALTER TABLE se_frms_matched_rule ALTER COLUMN rule_expression TYPE JSONB USING '[]'::jsonb;
ALTER TABLE se_frms_matched_rule ALTER COLUMN rule_score TYPE JSONB USING '[]'::jsonb;
ALTER TABLE se_frms_matched_rule ALTER COLUMN calculated_score TYPE JSONB USING '[]'::jsonb;

ALTER TABLE se_frms_matched_rule ALTER COLUMN rule_code SET DEFAULT '[]'::jsonb;
ALTER TABLE se_frms_matched_rule ALTER COLUMN rule_name SET DEFAULT '[]'::jsonb;
ALTER TABLE se_frms_matched_rule ALTER COLUMN rule_expression SET DEFAULT '[]'::jsonb;
ALTER TABLE se_frms_matched_rule ALTER COLUMN rule_score SET DEFAULT '[]'::jsonb;
ALTER TABLE se_frms_matched_rule ALTER COLUMN calculated_score SET DEFAULT '[]'::jsonb;

INSERT INTO se_frms_matched_rule (
    id, scoring_id, rule_code, rule_name, rule_expression, rule_score,
    calculated_score, status, created_by, created_date, updated_at
)
SELECT id, scoring_id, rule_code, rule_name, rule_expression, rule_score,
       calculated_score, status, created_by, created_date, updated_at
FROM matched_rule_jsonb_backup;

CREATE UNIQUE INDEX IF NOT EXISTS uk_matched_rule_scoring_id
    ON se_frms_matched_rule (scoring_id);

ALTER TABLE se_frms_scoring DROP COLUMN IF EXISTS matched_rules;
