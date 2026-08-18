ALTER TABLE se_frms_transaction_master
    ADD COLUMN IF NOT EXISTS external_transaction_id VARCHAR(150),
    ADD COLUMN IF NOT EXISTS ip_address VARCHAR(100),
    ADD COLUMN IF NOT EXISTS latitude NUMERIC(10, 7),
    ADD COLUMN IF NOT EXISTS longitude NUMERIC(10, 7),
    ADD COLUMN IF NOT EXISTS merchant_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS channel VARCHAR(50),
    ADD COLUMN IF NOT EXISTS transaction_type VARCHAR(100),
    ADD COLUMN IF NOT EXISTS currency VARCHAR(10),
    ADD COLUMN IF NOT EXISTS amount NUMERIC(19, 4),
    ADD COLUMN IF NOT EXISTS duplicate_transaction BOOLEAN,
    ADD COLUMN IF NOT EXISTS original_transaction_id UUID;

CREATE INDEX IF NOT EXISTS idx_transaction_external_id
    ON se_frms_transaction_master (external_transaction_id);

CREATE INDEX IF NOT EXISTS idx_transaction_status
    ON se_frms_transaction_master (status);

CREATE INDEX IF NOT EXISTS idx_transaction_merchant_id
    ON se_frms_transaction_master (merchant_id);

CREATE INDEX IF NOT EXISTS idx_transaction_user_id
    ON se_frms_transaction_master (user_id);

CREATE INDEX IF NOT EXISTS idx_transaction_channel
    ON se_frms_transaction_master (channel);

CREATE INDEX IF NOT EXISTS idx_transaction_type
    ON se_frms_transaction_master (transaction_type);

CREATE INDEX IF NOT EXISTS idx_transaction_currency
    ON se_frms_transaction_master (currency);

CREATE INDEX IF NOT EXISTS idx_transaction_created_date
    ON se_frms_transaction_master (created_date);
