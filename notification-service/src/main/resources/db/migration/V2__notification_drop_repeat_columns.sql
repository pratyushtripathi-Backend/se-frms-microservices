-- =====================================================================
-- Runs AUTOMATICALLY on service start (Flyway), once, after V1.
-- Everything these columns held now lives in notification_details:
--   notification_type                -> the keys of notification_details (DASHBOARD / EMAIL / SMS)
--   recipient, notification_status,
--   message_id, failure_reason,
--   retry_count                      -> notification_details.<EMAIL|SMS>.recipients.<admin>
--   alert_status                     -> no longer used (review happens in decision-service)
-- IF EXISTS makes it safe on every database state.
-- =====================================================================
ALTER TABLE se_frms_notification
    DROP COLUMN IF EXISTS notification_type,
    DROP COLUMN IF EXISTS recipient,
    DROP COLUMN IF EXISTS notification_status,
    DROP COLUMN IF EXISTS message_id,
    DROP COLUMN IF EXISTS failure_reason,
    DROP COLUMN IF EXISTS retry_count,
    DROP COLUMN IF EXISTS alert_status;
