CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS se_frms_decision_policy_cache (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    policy_id INTEGER NOT NULL,
    description VARCHAR(255) NOT NULL,
    allow_min_score INTEGER NOT NULL,
    allow_max_score INTEGER NOT NULL,
    review_min_score INTEGER NOT NULL,
    review_max_score INTEGER NOT NULL,
    block_min_score INTEGER NOT NULL,
    block_max_score INTEGER NOT NULL,
    status BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_decision_policy_cache_policy UNIQUE (policy_id),
    CONSTRAINT chk_policy_cache_allow_range CHECK (allow_min_score >= 0 AND allow_min_score <= allow_max_score),
    CONSTRAINT chk_policy_cache_review_range CHECK (review_min_score >= 0 AND review_min_score <= review_max_score),
    CONSTRAINT chk_policy_cache_block_range CHECK (block_min_score >= 0 AND block_min_score <= block_max_score),
    CONSTRAINT chk_policy_cache_range_order CHECK (
        review_min_score > allow_max_score
        AND block_min_score > review_max_score
    )
);

CREATE INDEX IF NOT EXISTS idx_decision_policy_cache_status
    ON se_frms_decision_policy_cache(status);

CREATE INDEX IF NOT EXISTS idx_decision_policy_cache_updated_at
    ON se_frms_decision_policy_cache(updated_at);
