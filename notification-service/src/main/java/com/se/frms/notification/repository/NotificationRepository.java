package com.se.frms.notification.repository;

import com.se.frms.notification.entity.Notification;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * All channel status changes are single UPDATE statements that touch only one
 * JSON entry (jsonb_set), so parallel threads (Kafka consumer, retry scheduler,
 * SMS delivery-status checks) can never overwrite each other's updates.
 * Do NOT update notification_details via save(entity) after the row exists.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    // readOnly transaction: the message column is a @Lob and cannot be read in auto-commit mode.
    @Transactional(readOnly = true)
    Optional<Notification> findFirstByTransactionIdAndFraudDecision(UUID transactionId, String fraudDecision);

    /**
     * Adds a recipient entry under EMAIL / SMS
     * only if that recipient is not already there. Returns 1 when added (caller
     * must send), 0 when it already existed (duplicate event - skip).
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE se_frms_notification SET
              notification_details = jsonb_set(
                  jsonb_set(
                      COALESCE(notification_details, CAST('{}' AS jsonb)),
                      ARRAY[CAST(:channel AS text)],
                      COALESCE(notification_details -> CAST(:channel AS text), CAST('{}' AS jsonb))
                          || CAST(:channelMeta AS jsonb)
                          || jsonb_build_object('recipients',
                                 COALESCE(notification_details -> CAST(:channel AS text) -> 'recipients', CAST('{}' AS jsonb)))),
                  ARRAY[CAST(:channel AS text), 'recipients', CAST(:recipient AS text)],
                  CAST(:entry AS jsonb)),
              updated_at = :now
            WHERE id = :id
              AND NOT COALESCE(jsonb_exists(notification_details -> CAST(:channel AS text) -> 'recipients',
                                            CAST(:recipient AS text)), false)
            """, nativeQuery = true)
    int addRecipientIfAbsent(@Param("id") UUID id,
                             @Param("channel") String channel,
                             @Param("recipient") String recipient,
                             @Param("channelMeta") String channelMetaJson,
                             @Param("entry") String entryJson,
                             @Param("now") LocalDateTime now);

    /**
     * Merges patchJson into one recipient entry (keys with JSON null are removed).
     * When expectedStatus is not null the update happens only if the entry
     * currently has that status. Returns number of rows updated (0 or 1).
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE se_frms_notification SET
              notification_details = jsonb_set(
                  notification_details,
                  ARRAY[CAST(:channel AS text), 'recipients', CAST(:recipient AS text)],
                  jsonb_strip_nulls(
                      (notification_details #> ARRAY[CAST(:channel AS text), 'recipients', CAST(:recipient AS text)])
                      || CAST(:patch AS jsonb))),
              updated_at = :now
            WHERE id = :id
              AND notification_details #> ARRAY[CAST(:channel AS text), 'recipients', CAST(:recipient AS text)] IS NOT NULL
              AND (CAST(:expectedStatus AS text) IS NULL
                   OR notification_details #>> ARRAY[CAST(:channel AS text), 'recipients', CAST(:recipient AS text), 'status']
                      = CAST(:expectedStatus AS text))
            """, nativeQuery = true)
    int updateRecipient(@Param("id") UUID id,
                        @Param("channel") String channel,
                        @Param("recipient") String recipient,
                        @Param("patch") String patchJson,
                        @Param("expectedStatus") String expectedStatus,
                        @Param("now") LocalDateTime now);

    /**
     * Atomically claims a FAILED recipient entry for a retry: FAILED -> PENDING and
     * retryCount + 1, only while retryCount < maxAttempts. Returns 1 if this caller
     * won the claim (and must send), 0 otherwise - so two threads can never re-send
     * the same entry.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE se_frms_notification SET
              notification_details = jsonb_set(
                  notification_details,
                  ARRAY[CAST(:channel AS text), 'recipients', CAST(:recipient AS text)],
                  (notification_details #> ARRAY[CAST(:channel AS text), 'recipients', CAST(:recipient AS text)])
                  || jsonb_build_object(
                         'status', 'PENDING',
                         'retryCount', COALESCE(CAST(notification_details #>> ARRAY[CAST(:channel AS text), 'recipients',
                                                     CAST(:recipient AS text), 'retryCount'] AS integer), 0) + 1)),
              updated_at = :now
            WHERE id = :id
              AND notification_details #>> ARRAY[CAST(:channel AS text), 'recipients', CAST(:recipient AS text), 'status'] = 'FAILED'
              AND COALESCE(CAST(notification_details #>> ARRAY[CAST(:channel AS text), 'recipients',
                                CAST(:recipient AS text), 'retryCount'] AS integer), 0) < :maxAttempts
            """, nativeQuery = true)
    int claimRecipientForRetry(@Param("id") UUID id,
                               @Param("channel") String channel,
                               @Param("recipient") String recipient,
                               @Param("maxAttempts") int maxAttempts,
                               @Param("now") LocalDateTime now);

    /** FAILED recipient entries that still have retries left: rows of [notificationId, channel, recipient]. */
    @Query(value = """
            SELECT CAST(n.id AS varchar) AS notification_id, c.key AS channel, r.key AS recipient
            FROM se_frms_notification n
            CROSS JOIN LATERAL jsonb_each(COALESCE(n.notification_details, CAST('{}' AS jsonb))) c
            CROSS JOIN LATERAL jsonb_each(
                CASE WHEN jsonb_typeof(c.value -> 'recipients') = 'object'
                     THEN c.value -> 'recipients' ELSE CAST('{}' AS jsonb) END) r
            WHERE r.value ->> 'status' = 'FAILED'
              AND COALESCE(CAST(r.value ->> 'retryCount' AS integer), 0) < :maxAttempts
            ORDER BY n.updated_at ASC
            LIMIT 100
            """, nativeQuery = true)
    List<Object[]> findRetryableFailedDeliveries(@Param("maxAttempts") int maxAttempts);
}
