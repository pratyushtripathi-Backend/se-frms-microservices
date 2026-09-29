-- =====================================================================
-- Runs AUTOMATICALLY on service start (Flyway), exactly once, before
-- Hibernate and the Kafka consumer start. Nothing to run by hand.
--
-- One se_frms_notification row per transaction + fraud decision:
--   notification_type    JSONB array  e.g. ["DASHBOARD","EMAIL","SMS"]
--   notification_details JSONB        per-channel / per-admin delivery state
--
-- Safe for every database state:
--   * table does not exist (fresh DB)      -> creates it in the new format
--   * table in old format (VARCHAR type)   -> backs it up, merges old rows
--                                             into one row per transaction
--   * table already in new format          -> does nothing
-- Flyway runs the whole file in one transaction: on any error nothing changes
-- and the service does not start (the error is in the startup log).
--
-- Old columns (recipient, notification_status, message_id, failure_reason,
-- retry_count, alert_status) are kept but unused; drop them later by hand with
-- database/V2_notification_drop_old_columns.sql once everything is verified.
-- =====================================================================
DO $migration$
DECLARE
    r record;
    type_column_type text;
BEGIN
    -- ---------- fresh database: create the table in the new format ----------
    IF to_regclass('public.se_frms_notification') IS NULL THEN
        CREATE TABLE se_frms_notification (
            id                   uuid PRIMARY KEY,
            transaction_id       uuid,
            notification_type    jsonb,
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
        RETURN;
    END IF;

    SELECT data_type INTO type_column_type
    FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'se_frms_notification' AND column_name = 'notification_type';

    -- ---------- already in the new format: nothing to do ----------
    IF type_column_type = 'jsonb' THEN
        RETURN;
    END IF;

    -- ---------- old format: migrate ----------

    -- 0. Backup of the complete old table
    CREATE TABLE IF NOT EXISTS se_frms_notification_backup_20260929 AS TABLE se_frms_notification;

    -- 1. New JSONB column
    ALTER TABLE se_frms_notification ADD COLUMN IF NOT EXISTS notification_details jsonb;

    -- 2. Drop old CHECK / UNIQUE constraints and unique indexes on notification_type / recipient
    FOR r IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'se_frms_notification'::regclass
          AND contype IN ('c', 'u')
          AND (pg_get_constraintdef(oid) ILIKE '%notification_type%'
               OR pg_get_constraintdef(oid) ILIKE '%recipient%')
    LOOP
        EXECUTE format('ALTER TABLE se_frms_notification DROP CONSTRAINT %I', r.conname);
    END LOOP;

    FOR r IN
        SELECT indexname FROM pg_indexes
        WHERE schemaname = 'public'
          AND tablename = 'se_frms_notification'
          AND indexdef ILIKE 'CREATE UNIQUE INDEX%'
          AND (indexdef ILIKE '%notification_type%' OR indexdef ILIKE '%recipient%')
    LOOP
        EXECUTE format('DROP INDEX IF EXISTS %I', r.indexname);
    END LOOP;

    -- 3. Old columns are no longer written by the new code -> must accept NULL
    FOR r IN
        SELECT column_name FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'se_frms_notification'
          AND column_name IN ('recipient', 'notification_status', 'message_id',
                              'failure_reason', 'retry_count', 'alert_status')
          AND is_nullable = 'NO'
    LOOP
        EXECUTE format('ALTER TABLE se_frms_notification ALTER COLUMN %I DROP NOT NULL', r.column_name);
    END LOOP;

    -- 4. One row to keep per transaction + decision (the DASHBOARD row, else the oldest)
    CREATE TEMP TABLE nt_merge ON COMMIT DROP AS
    SELECT transaction_id,
           fraud_decision,
           (array_agg(id ORDER BY CASE WHEN notification_type = 'DASHBOARD' THEN 0 ELSE 1 END,
                                  created_date, id))[1] AS keep_id,
           MIN(created_date) AS first_created,
           MAX(updated_at)   AS last_updated
    FROM se_frms_notification
    GROUP BY transaction_id, fraud_decision;

    -- 5. Build notification_details + channel list from all old rows of the group.
    --    The old entity stored "message" via @Lob, i.e. as a PostgreSQL large
    --    object with only its OID in the column - read the real text for the
    --    email / SMS body that goes into the JSON.
    ALTER TABLE se_frms_notification ADD COLUMN notification_type_new jsonb;

    UPDATE se_frms_notification n
    SET notification_type_new = agg.types,
        notification_details  = agg.details,
        created_date          = m.first_created,
        updated_at            = m.last_updated
    FROM nt_merge m
    CROSS JOIN LATERAL (
        SELECT
            (SELECT jsonb_agg(t.notification_type ORDER BY t.ord)
             FROM (SELECT DISTINCT o.notification_type,
                          CASE o.notification_type WHEN 'DASHBOARD' THEN 1 WHEN 'EMAIL' THEN 2 ELSE 3 END AS ord
                   FROM se_frms_notification o
                   WHERE o.transaction_id IS NOT DISTINCT FROM m.transaction_id
                     AND o.fraud_decision IS NOT DISTINCT FROM m.fraud_decision
                     AND o.notification_type IS NOT NULL) t) AS types,
            jsonb_strip_nulls(jsonb_build_object(
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

    -- 6. Remove the now-merged extra rows
    DELETE FROM se_frms_notification n
    WHERE NOT EXISTS (SELECT 1 FROM nt_merge m WHERE m.keep_id = n.id);

    -- 7. Old per-channel columns are no longer used
    UPDATE se_frms_notification
    SET recipient = NULL, notification_status = NULL, message_id = NULL,
        failure_reason = NULL, retry_count = NULL, alert_status = NULL;

    -- 8. notification_type: VARCHAR -> JSONB array
    ALTER TABLE se_frms_notification
        ALTER COLUMN notification_type TYPE jsonb
        USING COALESCE(notification_type_new, jsonb_build_array(notification_type));
    ALTER TABLE se_frms_notification DROP COLUMN notification_type_new;

    UPDATE se_frms_notification
    SET notification_details = CAST('{}' AS jsonb)
    WHERE notification_details IS NULL;

    -- 9. New rule: one row per transaction + decision
    ALTER TABLE se_frms_notification
        ADD CONSTRAINT uk_se_frms_notification_txn_decision UNIQUE (transaction_id, fraud_decision);
END
$migration$;
