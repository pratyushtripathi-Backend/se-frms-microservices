CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS se_frms_notification (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    transaction_id UUID NOT NULL,
    notification_type VARCHAR(20) NOT NULL CHECK (notification_type IN ('EMAIL', 'SMS', 'DASHBOARD')),
    recipient VARCHAR(255) NOT NULL,
    subject VARCHAR(255),
    message TEXT NOT NULL,
    fraud_decision VARCHAR(20) NOT NULL CHECK (fraud_decision IN ('ALLOW', 'REVIEW', 'BLOCK')),
    risk_score INTEGER NOT NULL CHECK (risk_score >= 0),
    notification_status VARCHAR(20) NOT NULL CHECK (notification_status IN ('PENDING', 'SENT', 'FAILED')),
    alert_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (alert_status IN ('PENDING', 'UNDER_REVIEW', 'RESOLVED')),
    failure_reason TEXT,
    status BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(100) NOT NULL,
    created_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_notification_transaction_type_decision_recipient
        UNIQUE (transaction_id, notification_type, fraud_decision, recipient)
);

CREATE INDEX IF NOT EXISTS idx_notification_transaction ON se_frms_notification(transaction_id);
CREATE INDEX IF NOT EXISTS idx_notification_type ON se_frms_notification(notification_type);
CREATE INDEX IF NOT EXISTS idx_notification_recipient ON se_frms_notification(recipient);
CREATE INDEX IF NOT EXISTS idx_notification_decision ON se_frms_notification(fraud_decision);
CREATE INDEX IF NOT EXISTS idx_notification_status ON se_frms_notification(notification_status);
CREATE INDEX IF NOT EXISTS idx_notification_created_date ON se_frms_notification(created_date);

-- Supports detailed dashboard alerts that can be longer than 255 characters.
ALTER TABLE se_frms_notification
    ALTER COLUMN message TYPE TEXT;

ALTER TABLE se_frms_notification
    ADD COLUMN IF NOT EXISTS alert_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
    CHECK (alert_status IN ('PENDING', 'UNDER_REVIEW', 'RESOLVED'));

ALTER TABLE se_frms_notification
    DROP CONSTRAINT IF EXISTS uk_notification_transaction_type_decision;

ALTER TABLE se_frms_notification
    DROP CONSTRAINT IF EXISTS uk_notification_transaction_type_decision_recipient;

ALTER TABLE se_frms_notification
    ADD CONSTRAINT uk_notification_transaction_type_decision_recipient
    UNIQUE (transaction_id, notification_type, fraud_decision, recipient);
