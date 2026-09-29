package com.se.frms.notification.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.se.frms.notification.dto.AdminNotificationRecipient;
import com.se.frms.notification.dto.EmailTemplateContent;
import com.se.frms.notification.dto.FraudEvent;
import com.se.frms.notification.dto.NotificationListResponse;
import com.se.frms.notification.dto.NotificationResponse;
import com.se.frms.notification.dto.SmsDeliveryStatus;
import com.se.frms.notification.entity.Notification;
import com.se.frms.notification.entity.Notification.ChannelDelivery;
import com.se.frms.notification.entity.Notification.DashboardDelivery;
import com.se.frms.notification.entity.Notification.NotificationDetails;
import com.se.frms.notification.entity.Notification.RecipientDelivery;
import com.se.frms.notification.repository.NotificationRepository;
import com.se.frms.notification.service.EmailSenderService;
import com.se.frms.notification.service.NotificationRecipientCacheService;
import com.se.frms.notification.service.NotificationService;
import com.se.frms.notification.service.NotificationTemplateCacheService;
import com.se.frms.notification.service.SmsSenderService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * One se_frms_notification row per transaction + fraud decision. The DASHBOARD,
 * EMAIL and SMS delivery state of every admin is kept inside notification_details
 * (JSONB) - see NotificationDetails. The row is inserted once (dashboard alert);
 * every later email / SMS / retry / delivery-status change is a single-entry
 * jsonb_set UPDATE in NotificationRepository, never a save() of the whole entity.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {
    private static final String SYSTEM_USER = "NOTIFICATION_SERVICE";
    private static final String DASHBOARD = "DASHBOARD";
    private static final String EMAIL = "EMAIL";
    private static final String SMS = "SMS";
    private static final String REVIEW = "REVIEW";
    private static final String BLOCK = "BLOCK";
    private static final String PENDING = "PENDING";
    private static final String SENT = "SENT";
    private static final String FAILED = "FAILED";
    // Carrier-confirmed outcomes for an already-accepted SMS. Kept distinct from
    // SENT/FAILED (which describe whether the send *request* itself succeeded)
    // so recoverFailedDeliveries() never re-sends an SMS that MSG24x7 accepted
    // but the telecom carrier later rejected/delivered.
    private static final String DELIVERED = "DELIVERED";
    private static final String DELIVERY_FAILED = "DELIVERY_FAILED";
    private static final int MAX_RETRY_ATTEMPTS = 3;
    // Separate, small cap for delivery-status polling attempts (not persisted -
    // passed through the scheduled lambda) so it never interacts with
    // retryCount / MAX_RETRY_ATTEMPTS, which govern re-sending a failed SMS.
    private static final int MAX_DELIVERY_STATUS_CHECKS = 3;
    /** API sort property -> DB column. Anything else falls back to created_date DESC. */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "createdDate", "n.created_date",
            "updatedAt", "n.updated_at",
            "riskScore", "n.risk_score",
            "fraudDecision", "n.fraud_decision",
            "transactionId", "n.transaction_id"
    );

    private static final String RECIPIENT_ENTRIES =
            "jsonb_each(CASE WHEN jsonb_typeof(c.value -> 'recipients') = 'object' "
                    + "THEN c.value -> 'recipients' ELSE CAST('{}' AS jsonb) END)";
    private final NotificationRepository notificationRepository;
    private final EmailSenderService emailSenderService;
    private final NotificationRecipientCacheService recipientCacheService;
    private final NotificationTemplateCacheService notificationTemplateCacheService;
    private final SmsSenderService smsSenderService;
    private final TaskScheduler notificationRetryTaskScheduler;
    // Pushes DASHBOARD alerts to "/topic/alerts" for connected WebSocket
    // clients - see findOrCreateNotification().
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    // Used only for the JSONB-aware list query in search().
    @PersistenceContext
    private EntityManager entityManager;

    @Value("${notification.email.enabled:false}")
    private boolean emailEnabled;

    @Value("${notification.sms.enabled:false}")
    private boolean smsEnabled;

    @Value("${notification.sms.msg24x7.review-template-id:}")
    private String reviewSmsTemplateId;

    @Value("${notification.sms.msg24x7.block-template-id:}")
    private String blockSmsTemplateId;

    @Override
    public void handleFraudEvent(FraudEvent event) {
        if (event == null || event.transactionId() == null || !StringUtils.hasText(event.fraudDecision())) {
            log.warn("Ignoring invalid fraud event");
            return;
        }

        String decision = normalizeDecision(event.fraudDecision());
        Map<String, Object> data = event.transactionData() == null ? Map.of() : event.transactionData();
        String message = buildDashboardMessage(event, decision, data);

        Notification notification = findOrCreateNotification(event, decision, dashboardSubject(decision), message);
        // Marks when the dashboard row is actually queryable, separate from
        // the slower email/SMS sends below (external network calls) — this
        // is the timestamp that matters for a "dashboard alert" latency
        // budget, not when handleFraudEvent() finishes entirely.
        log.info("Dashboard notification ready transactionId={}", event.transactionId());

        if (BLOCK.equals(decision) || REVIEW.equals(decision)) {
            sendConfiguredAdminEmails(notification.getId(), event, decision, data);
            sendConfiguredAdminSms(notification.getId(), event, decision);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getNotifications(
            UUID transactionId,
            String notificationType,
            String fraudDecision,
            String notificationStatus,
            String recipient,
            Pageable pageable
    ) {
        return search(transactionId, notificationType, fraudDecision, notificationStatus, recipient, pageable).map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getNotifications(
            UUID transactionId,
            String notificationType,
            String fraudDecision,
            String notificationStatus,
            String recipient,
            Integer page,
            Integer size
    ) {
        return getNotifications(
                transactionId, notificationType, fraudDecision, notificationStatus, recipient, pageable(page, size)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationListResponse> getNotificationsList(
            UUID transactionId,
            String notificationType,
            String fraudDecision,
            String notificationStatus,
            String recipient,
            Pageable pageable
    ) {
        return search(transactionId, notificationType, fraudDecision, notificationStatus, recipient, pageable).map(this::toListResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationListResponse> getNotificationsList(
            UUID transactionId,
            String notificationType,
            String fraudDecision,
            String notificationStatus,
            String recipient,
            Integer page,
            Integer size
    ) {
        return getNotificationsList(
                transactionId, notificationType, fraudDecision, notificationStatus, recipient, pageable(page, size)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationResponse getNotificationById(UUID notificationId) {
        return notificationRepository.findById(notificationId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Notification not found: " + notificationId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getNotificationsByTransactionId(UUID transactionId, Pageable pageable) {
        return getNotifications(transactionId, null, null, null, null, pageable);
    }

    /**
     * List query with optional filters (null / blank = not applied). Native SQL
     * because the filters look inside the JSONB columns:
     * notificationType - notification_details has that channel (DASHBOARD / EMAIL / SMS);
     * notificationStatus - dashboard or any email / SMS recipient has this status;
     * recipient - this email / phone is an EMAIL or SMS recipient.
     */
    @SuppressWarnings("unchecked")
    private Page<Notification> search(UUID transactionId, String notificationType, String fraudDecision,
                                      String notificationStatus, String recipient, Pageable pageable) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();

        if (transactionId != null) {
            where.append(" AND n.transaction_id = :transactionId");
            params.put("transactionId", transactionId);
        }
        if (StringUtils.hasText(notificationType)) {
            where.append(" AND COALESCE(jsonb_exists(n.notification_details, CAST(:notificationType AS text)), false)");
            params.put("notificationType", notificationType);
        }
        if (StringUtils.hasText(fraudDecision)) {
            where.append(" AND n.fraud_decision = :fraudDecision");
            params.put("fraudDecision", fraudDecision);
        }
        if (StringUtils.hasText(notificationStatus)) {
            where.append(" AND (n.notification_details -> 'DASHBOARD' ->> 'status' = :notificationStatus"
                    + " OR EXISTS (SELECT 1 FROM jsonb_each(COALESCE(n.notification_details, CAST('{}' AS jsonb))) c"
                    + " CROSS JOIN LATERAL " + RECIPIENT_ENTRIES + " r"
                    + " WHERE r.value ->> 'status' = :notificationStatus))");
            params.put("notificationStatus", notificationStatus);
        }
        if (StringUtils.hasText(recipient)) {
            where.append(" AND (COALESCE(jsonb_exists(n.notification_details -> 'EMAIL' -> 'recipients', CAST(:recipient AS text)), false)"
                    + " OR COALESCE(jsonb_exists(n.notification_details -> 'SMS' -> 'recipients', CAST(:recipient AS text)), false))");
            params.put("recipient", recipient);
        }

        Query contentQuery = entityManager.createNativeQuery(
                "SELECT n.* FROM se_frms_notification n" + where + orderBy(pageable.getSort()), Notification.class);
        Query countQuery = entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM se_frms_notification n" + where);
        params.forEach((name, value) -> {
            contentQuery.setParameter(name, value);
            countQuery.setParameter(name, value);
        });
        if (pageable.isPaged()) {
            contentQuery.setFirstResult((int) pageable.getOffset());
            contentQuery.setMaxResults(pageable.getPageSize());
        }

        List<Notification> content = contentQuery.getResultList();
        long total = ((Number) countQuery.getSingleResult()).longValue();
        return new PageImpl<>(content, pageable, total);
    }

    private String orderBy(Sort sort) {
        List<String> parts = new ArrayList<>();
        for (Sort.Order order : sort) {
            String column = SORT_COLUMNS.get(order.getProperty());
            if (column != null) {
                parts.add(column + (order.isAscending() ? " ASC" : " DESC"));
            }
        }
        if (parts.isEmpty()) {
            parts.add("n.created_date DESC");
        }
        parts.add("n.id");
        return " ORDER BY " + String.join(", ", parts);
    }

    /** size == null -> everything, newest first, no pagination. */
    private Pageable pageable(Integer page, Integer size) {
        Sort sort = Sort.by(Sort.Direction.DESC, "createdDate");
        return size == null
                ? Pageable.unpaged(sort)
                : PageRequest.of(page == null ? 0 : page, size, sort);
    }

    /**
     * Returns the existing row for transaction + decision (duplicate Kafka event),
     * or inserts it with the DASHBOARD channel and pushes it over WebSocket.
     */
    private Notification findOrCreateNotification(FraudEvent event, String decision, String subject, String message) {
        Notification existing = notificationRepository
                .findFirstByTransactionIdAndFraudDecision(event.transactionId(), decision).orElse(null);
        if (existing != null) {
            return existing;
        }

        LocalDateTime now = LocalDateTime.now();
        NotificationDetails details = new NotificationDetails();
        details.setDashboard(new DashboardDelivery(SENT));

        Notification notification = new Notification();
        notification.setTransactionId(event.transactionId());
        notification.setNotificationDetails(details);
        notification.setSubject(subject);
        notification.setMessage(message);
        notification.setFraudDecision(decision);
        notification.setRiskScore(event.totalRiskScore() == null ? 0 : event.totalRiskScore());
        notification.setStatus(true);
        notification.setCreatedBy(SYSTEM_USER);
        notification.setCreatedDate(now);
        notification.setUpdatedAt(now);

        Notification saved;
        try {
            saved = notificationRepository.saveAndFlush(notification);
        } catch (DataIntegrityViolationException ex) {
            // Same event processed concurrently - unique (transaction_id, fraud_decision) kept one row.
            return notificationRepository
                    .findFirstByTransactionIdAndFraudDecision(event.transactionId(), decision)
                    .orElseThrow(() -> ex);
        }
        log.info("Notification recorded transactionId={}, type={}, status={}",
                event.transactionId(), DASHBOARD, SENT);

        messagingTemplate.convertAndSend("/topic/alerts", toResponse(saved));
        log.info("Dashboard alert pushed over WebSocket transactionId={}", event.transactionId());
        return saved;
    }

    private void sendConfiguredAdminEmails(UUID notificationId, FraudEvent event, String decision, Map<String, Object> data) {
        if (!emailEnabled) {
            log.info("Email alert is disabled; skipping transactionId={}", event.transactionId());
            return;
        }

        EmailTemplateContent cachedTemplate = notificationTemplateCacheService.getEmailTemplate(decision);
        String subject = cachedTemplate == null ? emailSubject(decision) : cachedTemplate.subject();
        String message = cachedTemplate == null
                ? buildEmailMessage(event, decision, data)
                : renderEmailTemplate(cachedTemplate.body(), event, decision, data);

        recipientCacheService.getCachedRecipients().stream()
                .map(AdminNotificationRecipient::email)
                .map(email -> email == null ? "" : email.trim())
                .filter(StringUtils::hasText)
                .distinct()
                .forEach(recipient -> sendEmail(notificationId, event, recipient, subject, message));
    }

    private void sendEmail(UUID notificationId, FraudEvent event, String recipient, String subject, String message) {
        ObjectNode channelMeta = objectMapper.createObjectNode().put("subject", subject).put("message", message);
        if (!addRecipient(notificationId, EMAIL, recipient, channelMeta)) {
            return; // already handled for this transaction (duplicate event)
        }

        try {
            emailSenderService.send(recipient, subject, message);
            updateRecipient(notificationId, EMAIL, recipient, statusPatch(SENT, null), null);
            log.info("Email alert sent transactionId={}, recipient={}", event.transactionId(), recipient);
        } catch (Exception ex) {
            updateRecipient(notificationId, EMAIL, recipient, statusPatch(FAILED, ex.getMessage()), null);
            log.error("Email alert failed transactionId={}, recipient={}", event.transactionId(), recipient, ex);
            scheduleRetry(notificationId, EMAIL, recipient, 0);
        }
    }

    private void sendConfiguredAdminSms(UUID notificationId, FraudEvent event, String decision) {
        if (!smsEnabled) {
            log.info("SMS alert is disabled; skipping transactionId={}", event.transactionId());
            return;
        }
        String templateId = REVIEW.equals(decision) ? reviewSmsTemplateId : blockSmsTemplateId;
        String message = buildSmsMessage(event, decision);
        recipientCacheService.getCachedRecipients().stream()
                .map(AdminNotificationRecipient::phoneNumber)
                .map(phone -> phone == null ? "" : phone.trim())
                .filter(StringUtils::hasText)
                .distinct()
                .forEach(recipient -> sendSms(notificationId, event, decision, recipient, message, templateId));
    }

    private void sendSms(UUID notificationId, FraudEvent event, String decision, String recipient,
                         String message, String templateId) {
        ObjectNode channelMeta = objectMapper.createObjectNode().put("message", message);
        if (!addRecipient(notificationId, SMS, recipient, channelMeta)) {
            return; // already handled for this transaction (duplicate event)
        }

        String messageId;
        try {
            messageId = smsSenderService.send(
                    recipient, message, templateId, "FRMS-" + decision + "-" + event.transactionId());
        } catch (Exception ex) {
            updateRecipient(notificationId, SMS, recipient, statusPatch(FAILED, ex.getMessage()), null);
            log.error("SMS alert failed transactionId={}, recipient={}", event.transactionId(), recipient, ex);
            scheduleRetry(notificationId, SMS, recipient, 0);
            return;
        }

        updateRecipient(notificationId, SMS, recipient, statusPatch(SENT, null).put("messageId", messageId), null);
        if (StringUtils.hasText(messageId)) {
            scheduleDeliveryStatusCheck(notificationId, recipient, 0);
        }
    }

    /**
     * Quick retry delays are 2s, 5s and 30s. This is executed outside the Kafka
     * consumer thread, therefore a failed provider call never delays a fraud decision.
     */
    private void scheduleRetry(UUID notificationId, String channel, String recipient, int currentRetryCount) {
        if (currentRetryCount >= MAX_RETRY_ATTEMPTS) {
            return;
        }
        long delayMillis = switch (currentRetryCount) {
            case 0 -> 2_000L;
            case 1 -> 5_000L;
            default -> 30_000L;
        };
        notificationRetryTaskScheduler.schedule(
                () -> retryDelivery(notificationId, channel, recipient), Instant.now().plusMillis(delayMillis));
    }

    private void retryDelivery(UUID notificationId, String channel, String recipient) {
        // Atomic FAILED -> PENDING + retryCount++; if another thread already
        // claimed it (or retries are exhausted) this returns 0 and we stop.
        if (notificationRepository.claimRecipientForRetry(
                notificationId, channel, recipient, MAX_RETRY_ATTEMPTS, LocalDateTime.now()) == 0) {
            return;
        }
        Notification notification = notificationRepository.findById(notificationId).orElse(null);
        if (notification == null || notification.getNotificationDetails() == null) {
            return;
        }
        NotificationDetails details = notification.getNotificationDetails();
        ChannelDelivery channelDelivery = details.channelFor(channel);
        RecipientDelivery entry = details.recipientFor(channel, recipient);
        if (channelDelivery == null || entry == null) {
            return;
        }
        int retryCount = entry.getRetryCount() == null ? 0 : entry.getRetryCount();

        String messageId = null;
        try {
            if (EMAIL.equals(channel)) {
                emailSenderService.send(recipient, channelDelivery.getSubject(), channelDelivery.getMessage());
            } else if (SMS.equals(channel)) {
                String templateId = REVIEW.equals(notification.getFraudDecision())
                        ? reviewSmsTemplateId : blockSmsTemplateId;
                messageId = smsSenderService.send(recipient, channelDelivery.getMessage(), templateId,
                        "FRMS-" + notification.getFraudDecision() + "-RETRY-" + notification.getId());
            } else {
                return;
            }
        } catch (Exception ex) {
            updateRecipient(notificationId, channel, recipient, statusPatch(FAILED, ex.getMessage()), null);
            log.warn("Notification retry failed notificationId={}, channel={}, recipient={}, retryCount={}",
                    notificationId, channel, recipient, retryCount);
            scheduleRetry(notificationId, channel, recipient, retryCount);
            return;
        }

        ObjectNode patch = statusPatch(SENT, null);
        if (SMS.equals(channel)) {
            patch.put("messageId", messageId);
        }
        updateRecipient(notificationId, channel, recipient, patch, null);
        log.info("Notification retry succeeded notificationId={}, channel={}, recipient={}, retryCount={}",
                notificationId, channel, recipient, retryCount);

        if (SMS.equals(channel) && StringUtils.hasText(messageId)) {
            scheduleDeliveryStatusCheck(notificationId, recipient, 0);
        }
    }

    /** Recover retryable failures left behind if the service restarted mid-retry. */
    @Scheduled(initialDelay = 60_000L, fixedDelay = 60_000L)
    public void recoverFailedDeliveries() {
        List<Object[]> failedDeliveries = notificationRepository.findRetryableFailedDeliveries(MAX_RETRY_ATTEMPTS);
        failedDeliveries.forEach(row -> {
            UUID notificationId = UUID.fromString(String.valueOf(row[0]));
            String channel = String.valueOf(row[1]);
            String recipient = String.valueOf(row[2]);
            notificationRetryTaskScheduler.schedule(
                    () -> retryDelivery(notificationId, channel, recipient), Instant.now());
        });
    }

    /**
     * Checks whether an already-accepted SMS was actually delivered by the telecom
     * carrier, using the MessageId MSG24x7 returned at send time. This does not
     * re-send anything - it only reconciles the recipient's status from SENT to
     * DELIVERED/DELIVERY_FAILED. Runs on notificationRetryTaskScheduler so it never
     * blocks the fraud-event/Kafka consumer thread. The attempt counter is passed
     * through the lambda (not persisted) so it can't interfere with retryCount,
     * which is reserved for send-failure retries.
     */
    private void scheduleDeliveryStatusCheck(UUID notificationId, String recipient, int attempt) {
        if (attempt >= MAX_DELIVERY_STATUS_CHECKS) {
            log.warn("Giving up on delivery status confirmation notificationId={}, recipient={} after {} checks",
                    notificationId, recipient, attempt);
            return;
        }
        long delayMillis = switch (attempt) {
            case 0 -> 3_000L;
            case 1 -> 7_000L;
            default -> 15_000L;
        };
        notificationRetryTaskScheduler.schedule(
                () -> checkSmsDeliveryStatus(notificationId, recipient, attempt), Instant.now().plusMillis(delayMillis));
    }

    private void checkSmsDeliveryStatus(UUID notificationId, String recipient, int attempt) {
        Notification notification = notificationRepository.findById(notificationId).orElse(null);
        RecipientDelivery entry = notification == null || notification.getNotificationDetails() == null
                ? null
                : notification.getNotificationDetails().recipientFor(SMS, recipient);
        if (entry == null || !SENT.equals(entry.getStatus()) || !StringUtils.hasText(entry.getMessageId())) {
            return;
        }

        SmsDeliveryStatus result;
        try {
            result = smsSenderService.checkDeliveryStatus(entry.getMessageId());
        } catch (Exception ex) {
            log.warn("Delivery status check errored notificationId={}, attempt={}", notificationId, attempt, ex);
            scheduleDeliveryStatusCheck(notificationId, recipient, attempt + 1);
            return;
        }

        // expectedStatus = SENT: never overwrite an entry a retry changed meanwhile.
        if (result.delivered()) {
            updateRecipient(notificationId, SMS, recipient, statusPatch(DELIVERED, null), SENT);
            log.info("SMS delivery confirmed by carrier notificationId={}, recipient={}", notificationId, recipient);
        } else if (result.failed()) {
            updateRecipient(notificationId, SMS, recipient, statusPatch(DELIVERY_FAILED, result.reason()), SENT);
            log.warn("SMS rejected by carrier notificationId={}, recipient={}, reason={}",
                    notificationId, recipient, result.reason());
        } else {
            // Still in flight at the carrier (e.g. pending) - check again if attempts remain.
            scheduleDeliveryStatusCheck(notificationId, recipient, attempt + 1);
        }
    }

    /** Adds a PENDING entry for this recipient; false if it already existed (duplicate event). */
    private boolean addRecipient(UUID notificationId, String channel, String recipient, ObjectNode channelMeta) {
        ObjectNode entry = objectMapper.createObjectNode().put("status", PENDING).put("retryCount", 0);
        return notificationRepository.addRecipientIfAbsent(notificationId, channel, recipient,
                channelMeta.toString(), entry.toString(), LocalDateTime.now()) > 0;
    }

    private void updateRecipient(UUID notificationId, String channel, String recipient,
                                 ObjectNode patch, String expectedStatus) {
        notificationRepository.updateRecipient(notificationId, channel, recipient,
                patch.toString(), expectedStatus, LocalDateTime.now());
    }

    /** failureReason null -> JSON null -> removed from the entry (e.g. after a successful retry). */
    private ObjectNode statusPatch(String status, String failureReason) {
        ObjectNode patch = objectMapper.createObjectNode().put("status", status);
        if (failureReason == null && !FAILED.equals(status) && !DELIVERY_FAILED.equals(status)) {
            patch.putNull("failureReason");
        } else {
            patch.put("failureReason", truncateFailureReason(failureReason));
        }
        return patch;
    }

    private String truncateFailureReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "Delivery failed without an error message";
        }
        return reason.length() <= 2000 ? reason : reason.substring(0, 2000);
    }

    private String normalizeDecision(String fraudDecision) {
        if ("ALLOW".equalsIgnoreCase(fraudDecision)) {
            return "ALLOW";
        }
        if (REVIEW.equalsIgnoreCase(fraudDecision)) {
            return REVIEW;
        }
        return BLOCK;
    }

    private String dashboardSubject(String decision) {
        return switch (decision) {
            case "ALLOW" -> "Transaction Allowed";
            case REVIEW -> "Transaction Requires Review";
            default -> "High Risk Transaction Blocked";
        };
    }

    private String emailSubject(String decision) {
        return switch (decision) {
            case REVIEW -> "[FRMS] Review Required: Transaction Requires Attention";
            default -> "[FRMS] Block Alert: High-Risk Transaction Detected";
        };
    }

    /**
     * IMPORTANT: this text must match the DLT-approved MSG24x7 template EXACTLY
     * (only the two {#var#} values - Reference ID, then Score - may differ),
     * otherwise the telecom operator's DLT filter will reject the SMS even if
     * MSG24x7 accepts the API call. Keep this in sync with the "review" and
     * "block" templates in the MSG24x7 Manage Template dashboard.
     */
    private String buildSmsMessage(FraudEvent event, String decision) {
        // Line breaks are part of the DLT-approved template text, not just
        // cosmetic - a single-line (space-separated) version of this same
        // wording was accepted by MSG24x7's API but silently never delivered,
        // because it no longer matched the registered template exactly. Keep
        // these \n exactly as tested/confirmed delivered.
        int riskScore = event.totalRiskScore() == null ? 0 : event.totalRiskScore();
        if (REVIEW.equals(decision)) {
            return "Dear Admin,\nA case has been flagged for your review.\nReference ID: "
                    + event.transactionId() + "\nScore: " + riskScore
                    + "\nPlease review the case in the Admin Panel.\nRegards,\nSecureedge Fintech Pvt Ltd";
        }
        return "Dear Admin,\nA case has been flagged for further attention.\nReference ID: "
                + event.transactionId() + "\nScore: " + riskScore
                + "\nPlease review the case in the Admin Panel.\nRegards,\nSecureedge Fintech Pvt Ltd";
    }

    /**
     * Temporary code-based templates. These can later be moved to a managed
     * notification-template table without changing the delivery flow.
     */
    private String buildEmailMessage(FraudEvent event, String decision, Map<String, Object> data) {
        String amount = value(data, "amount", "N/A");
        String currency = value(data, "currency", "");
        String channel = value(data, "channel", "N/A");
        String transactionDate = formatTransactionDate(value(data, "transactionDate", null));
        String location = value(data, "location", null);
        if (!StringUtils.hasText(location)) {
            location = "Latitude: " + value(data, "latitude", "N/A")
                    + ", Longitude: " + value(data, "longitude", "N/A");
        }
        String reason = StringUtils.hasText(event.decisionReason())
                ? event.decisionReason()
                : "Decision calculated from configured risk-score policy.";
        String heading = REVIEW.equals(decision)
                ? "A transaction requires manual fraud review."
                : "A high-risk transaction has been blocked.";
        String action = REVIEW.equals(decision)
                ? "Review this transaction and update the alert status."
                : "Verify the blocked transaction and take any required follow-up action.";

        return "Dear Admin,"
                + "\n\n" + heading
                + "\n\nTransaction ID: " + event.transactionId()
                + "\nTransaction Date: " + transactionDate
                + "\nAmount: " + amount + (StringUtils.hasText(currency) ? " " + currency : "")
                + "\nChannel: " + channel
                + "\nLocation: " + location
                + "\nDecision: " + decision
                + "\nRisk Score: " + (event.totalRiskScore() == null ? 0 : event.totalRiskScore())
                + "\nReason: " + reason
                + "\nTriggered Rules: " + (event.triggeredRules() == null || event.triggeredRules().isEmpty()
                        ? "None" : event.triggeredRules())
                + "\n\nAction required: " + action
                + "\n\nRegards,"
                + "\nSecure Edge Fintech Pvt. Ltd.";
    }

    private String renderEmailTemplate(String template, FraudEvent event, String decision, Map<String, Object> data) {
        String location = value(data, "location", null);
        if (!StringUtils.hasText(location)) {
            location = "Latitude: " + value(data, "latitude", "N/A")
                    + ", Longitude: " + value(data, "longitude", "N/A");
        }
        String reason = StringUtils.hasText(event.decisionReason())
                ? event.decisionReason()
                : "Decision calculated from configured risk-score policy.";
        String rules = event.triggeredRules() == null || event.triggeredRules().isEmpty()
                ? "None" : event.triggeredRules().toString();

        return template
                .replace("{{transactionId}}", event.transactionId().toString())
                .replace("{{transactionDate}}", formatTransactionDate(value(data, "transactionDate", null)))
                .replace("{{amount}}", value(data, "amount", "N/A"))
                .replace("{{currency}}", value(data, "currency", ""))
                .replace("{{channel}}", value(data, "channel", "N/A"))
                .replace("{{location}}", location)
                .replace("{{decision}}", decision)
                .replace("{{riskScore}}", String.valueOf(event.totalRiskScore() == null ? 0 : event.totalRiskScore()))
                .replace("{{decisionReason}}", reason)
                .replace("{{triggeredRules}}", rules);
    }

    /**
     * transactionData carries the transaction's real created date/time as an
     * ISO-8601 string (set once in transaction-service, unchanged all the way
     * through FraudEvent) - formatted here for the email body. Falls back to
     * the raw value if it's ever missing/unparsable so a legacy event (sent
     * before this field existed) doesn't break the email.
     */
    private String formatTransactionDate(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return "N/A";
        }
        try {
            LocalDateTime parsed = LocalDateTime.parse(rawValue);
            return parsed.format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a"));
        } catch (DateTimeParseException ex) {
            return rawValue;
        }
    }

    private String buildDashboardMessage(FraudEvent event, String decision, Map<String, Object> data) {
        String amount = value(data, "amount", "N/A");
        String currency = value(data, "currency", "");
        String channel = value(data, "channel", "N/A");
        String location = value(data, "location", null);
        if (!StringUtils.hasText(location)) {
            location = "Latitude: " + value(data, "latitude", "N/A")
                    + ", Longitude: " + value(data, "longitude", "N/A");
        }
        String reason = StringUtils.hasText(event.decisionReason())
                ? event.decisionReason()
                : "Decision calculated from configured risk-score policy.";
        return "Transaction ID: " + event.transactionId()
                + "\nAmount: " + amount + (StringUtils.hasText(currency) ? " " + currency : "")
                + "\nChannel: " + channel
                + "\nLocation: " + location
                + "\nDecision: " + decision
                + "\nRisk Score: " + (event.totalRiskScore() == null ? 0 : event.totalRiskScore())
                + "\nReason: " + reason
                + "\nTriggered Rules: " + (event.triggeredRules() == null || event.triggeredRules().isEmpty()
                        ? "None" : event.triggeredRules());
    }

    private String value(Map<String, Object> data, String key, String fallback) {
        Object value = data.get(key);
        return value == null || !StringUtils.hasText(value.toString()) ? fallback : value.toString();
    }

    private NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getTransactionId(),
                notification.getFraudDecision(),
                notification.getRiskScore(),
                notification.getSubject(),
                notification.getMessage(),
                toChannels(notification.getNotificationDetails()),
                notification.getStatus(),
                notification.getCreatedBy(),
                notification.getCreatedDate(),
                notification.getUpdatedAt()
        );
    }

    private NotificationListResponse toListResponse(Notification notification) {
        return new NotificationListResponse(
                notification.getId(),
                notification.getTransactionId(),
                notification.getFraudDecision(),
                notification.getRiskScore(),
                notification.getSubject(),
                toChannels(notification.getNotificationDetails()),
                notification.getCreatedBy(),
                notification.getCreatedDate(),
                notification.getUpdatedAt()
        );
    }

    /** notification_details -> [DASHBOARD, EMAIL, SMS] (only channels that exist), recipients as a list of emails / phone numbers. */
    private List<NotificationResponse.Channel> toChannels(NotificationDetails details) {
        List<NotificationResponse.Channel> channels = new ArrayList<>();
        if (details == null) {
            return channels;
        }
        if (details.getDashboard() != null) {
            channels.add(new NotificationResponse.Channel(DASHBOARD, null, null));
        }
        if (details.getEmail() != null) {
            channels.add(new NotificationResponse.Channel(
                    EMAIL, details.getEmail().getSubject(), toRecipients(details.getEmail())));
        }
        if (details.getSms() != null) {
            channels.add(new NotificationResponse.Channel(SMS, null, toRecipients(details.getSms())));
        }
        return channels;
    }

    private List<String> toRecipients(ChannelDelivery channel) {
        return channel.getRecipients() == null ? List.of() : new ArrayList<>(channel.getRecipients().keySet());
    }
}
