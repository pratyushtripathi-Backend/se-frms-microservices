-- =====================================================================
-- Runs AUTOMATICALLY on service start (Flyway), once, after V2.
-- Moves notification read / unread state from the browser to the database.
-- One shared flag per notification (all admins share it):
--   is_read  - false for every new dashboard alert; set true by
--              PATCH /api/v1/notifications/read-all
--   read_at  - when it was marked read
-- Notifications that already exist when this runs are treated as read, so the
-- bell does not jump to "99+" right after the upgrade. Only alerts that arrive
-- from now on count as unread.
-- IF NOT EXISTS / IS NULL checks make it safe on every database state.
-- =====================================================================
ALTER TABLE se_frms_notification ADD COLUMN IF NOT EXISTS is_read boolean;
ALTER TABLE se_frms_notification ADD COLUMN IF NOT EXISTS read_at timestamp(6);

UPDATE se_frms_notification
SET is_read = true,
    read_at = COALESCE(read_at, now())
WHERE is_read IS NULL;

ALTER TABLE se_frms_notification ALTER COLUMN is_read SET DEFAULT false;
ALTER TABLE se_frms_notification ALTER COLUMN is_read SET NOT NULL;

-- Keeps the unread count / mark-all-read fast however large the table grows.
CREATE INDEX IF NOT EXISTS idx_se_frms_notification_unread
    ON se_frms_notification (created_date)
    WHERE is_read = false;
