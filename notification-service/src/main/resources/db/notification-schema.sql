-- =====================================================================
-- notification-service DB setup. Runs AUTOMATICALLY on every service start
-- (spring.sql.init, before Hibernate and the Kafka consumer). Nothing to run
-- by hand, no extra tables are kept in the DB. Safe to run again and again:
--   * table does not exist            -> creates it (one row per transaction)
--   * table in the old format         -> merges the old per-channel rows into
--     (one row per channel/admin)        one row per transaction + decision,
--                                        channel-wise data in notification_details
--   * table already in the new format -> nothing changes
-- The whole block is ONE statement: on any error nothing changes and the
-- service does not start (the error is in the startup log).
-- Keep this file as a single DO block (no ';' separated statements outside it).
-- =====================================================================
DO $notification_schema$
DECLARE
    type_column_type text;
BEGIN
    -- ---------- fresh database: create the table ----------
    IF to_regclass('public.se_frms_notification') IS NULL THEN
        CREATE TABLE se_frms_notification (
            id                   uuid PRIMARY KEY,
            transaction_id       uuid,
            notification_details jsonb,
            subject              varchar(255),
            message              text,
            fraud_decision       varchar(255),
            risk_score           integer,
            status               boolean,
            created_by           varchar(255),
            created_date         timestamp(6),
            updated_at           timestamp(6),
            CONSTRAINT uk_se_frms_notification_txn_decision UNIQUE (transaction_id, fraud_decision)
        );
    ELSE
        SELECT data_type INTO type_column_type
        FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'se_frms_notification'
          AND column_name = 'notification_type';

        -- ---------- old format (notification_type VARCHAR): merge rows ----------
        IF type_column_type IS NOT NULL AND type_column_type <> 'jsonb' THEN
            ALTER TABLE se_frms_notification ADD COLUMN IF NOT EXISTS notification_details jsonb;

            -- one row to keep per transaction + decision (the DASHBOARD row, else the oldest)
            CREATE TEMP TABLE nt_merge ON COMMIT DROP AS
            SELECT transaction_id,
                   fraud_decision,
                   (array_agg(id ORDER BY CASE WHEN notification_type = 'DASHBOARD' THEN 0 ELSE 1 END,
                                          created_date, id))[1] AS keep_id,
                   MIN(created_date) AS first_created,
                   MAX(updated_at)   AS last_updated
            FROM se_frms_notification
            GROUP BY transaction_id, fraud_decision;

            -- The old entity stored "message" via @Lob (a PostgreSQL large object,
            -- only its OID in the column) - read the real text for the email / SMS body.
            UPDATE se_frms_notification n
            SET notification_details = agg.details,
                created_date         = m.first_created,
                updated_at           = m.last_updated
            FROM nt_merge m
            CROSS JOIN LATERAL (
                SELECT jsonb_strip_nulls(jsonb_build_object(
                    'DASHBOARD',
                    (SELECT jsonb_build_object('status', MAX(o.notification_status))
                     FROM se_frms_notification o
                     WHERE o.transaction_id IS NOT DISTINCT FROM m.transaction_id
                       AND o.fraud_decision IS NOT DISTINCT FROM m.fraud_decision
                       AND o.notification_type = 'DASHBOARD'
                     HAVING COUNT(*) > 0),
                    'EMAIL',
                    (SELECT jsonb_build_object(
                                'subject', MAX(o.subject),
                                'message', MAX(CASE
                                    WHEN o.message ~ '^[0-9]+$'
                                         AND EXISTS (SELECT 1 FROM pg_largeobject_metadata lo
                                                     WHERE lo.oid = CAST(o.message AS oid))
                                        THEN convert_from(lo_get(CAST(o.message AS oid)), 'UTF8')
                                    ELSE o.message END),
                                'recipients', jsonb_object_agg(o.recipient, jsonb_build_object(
                                    'status', o.notification_status,
                                    'retryCount', COALESCE(o.retry_count, 0),
                                    'failureReason', o.failure_reason)))
                     FROM se_frms_notification o
                     WHERE o.transaction_id IS NOT DISTINCT FROM m.transaction_id
                       AND o.fraud_decision IS NOT DISTINCT FROM m.fraud_decision
                       AND o.notification_type = 'EMAIL'
                       AND o.recipient IS NOT NULL
                     HAVING COUNT(*) > 0),
                    'SMS',
                    (SELECT jsonb_build_object(
                                'message', MAX(CASE
                                    WHEN o.message ~ '^[0-9]+$'
                                         AND EXISTS (SELECT 1 FROM pg_largeobject_metadata lo
                                                     WHERE lo.oid = CAST(o.message AS oid))
                                        THEN convert_from(lo_get(CAST(o.message AS oid)), 'UTF8')
                                    ELSE o.message END),
                                'recipients', jsonb_object_agg(o.recipient, jsonb_build_object(
                                    'status', o.notification_status,
                                    'messageId', o.message_id,
                                    'retryCount', COALESCE(o.retry_count, 0),
                                    'failureReason', o.failure_reason)))
                     FROM se_frms_notification o
                     WHERE o.transaction_id IS NOT DISTINCT FROM m.transaction_id
                       AND o.fraud_decision IS NOT DISTINCT FROM m.fraud_decision
                       AND o.notification_type = 'SMS'
                       AND o.recipient IS NOT NULL
                     HAVING COUNT(*) > 0)
                )) AS details
            ) agg
            WHERE n.id = m.keep_id;

            -- remove the now-merged extra rows
            DELETE FROM se_frms_notification n
            WHERE NOT EXISTS (SELECT 1 FROM nt_merge m WHERE m.keep_id = n.id);
        END IF;

        -- ---------- drop columns whose data now lives in notification_details ----------
        IF EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'se_frms_notification'
                     AND column_name IN ('notification_type', 'recipient', 'notification_status',
                                         'message_id', 'failure_reason', 'retry_count', 'alert_status')) THEN
            ALTER TABLE se_frms_notification
                DROP COLUMN IF EXISTS notification_type,
                DROP COLUMN IF EXISTS recipient,
                DROP COLUMN IF EXISTS notification_status,
                DROP COLUMN IF EXISTS message_id,
                DROP COLUMN IF EXISTS failure_reason,
                DROP COLUMN IF EXISTS retry_count,
                DROP COLUMN IF EXISTS alert_status;
        END IF;

        UPDATE se_frms_notification
        SET notification_details = CAST('{}' AS jsonb)
        WHERE notification_details IS NULL;

        -- one row per transaction + decision
        IF NOT EXISTS (SELECT 1 FROM pg_constraint
                       WHERE conname = 'uk_se_frms_notification_txn_decision') THEN
            ALTER TABLE se_frms_notification
                ADD CONSTRAINT uk_se_frms_notification_txn_decision UNIQUE (transaction_id, fraud_decision);
        END IF;
    END IF;

    -- leftovers of the earlier Flyway-based setup - no longer used
    DROP TABLE IF EXISTS flyway_schema_history;
    DROP TABLE IF EXISTS se_frms_notification_backup_20260929;
END
$notification_schema$
