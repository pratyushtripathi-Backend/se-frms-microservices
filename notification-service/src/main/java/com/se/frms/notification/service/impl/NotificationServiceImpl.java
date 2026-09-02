package com.se.frms.notification.service.impl;

import com.se.frms.notification.dto.FraudEvent;
import com.se.frms.notification.dto.EmailTemplateContent;
import com.se.frms.notification.dto.NotificationResponse;
import com.se.frms.notification.dto.UpdateAlertStatusRequest;
import com.se.frms.notification.entity.Notification;
import com.se.frms.notification.repository.NotificationRepository;
import com.se.frms.notification.service.EmailSenderService;
import com.se.frms.notification.service.NotificationService;
import com.se.frms.notification.service.NotificationRecipientCacheService;
import com.se.frms.notification.service.NotificationTemplateCacheService;
import com.se.frms.notification.service.SmsSenderService;
import com.se.frms.notification.dto.AdminNotificationRecipient;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {
    private static final String SYSTEM_USER = "NOTIFICATION_SERVICE";
    private static final String DASHBOARD = "DASHBOARD";
    private static final String EMAIL = "EMAIL";
    private static final String SMS = "SMS";
    private static final String FRAUD_ADMIN_DASHBOARD = "FRAUD_ADMIN_DASHBOARD";
    private static final String REVIEW = "REVIEW";
    private static final String BLOCK = "BLOCK";
    private static final String PENDING = "PENDING";
    private static final String SENT = "SENT";
    private static final String FAILED = "FAILED";
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private final NotificationRepository notificationRepository;
    private final EmailSenderService emailSenderService;
    private final NotificationRecipientCacheService recipientCacheService;
    private final NotificationTemplateCacheService notificationTemplateCacheService;
    private final SmsSenderService smsSenderService;
    private final TaskScheduler notificationRetryTaskScheduler;
    @Qualifier("notificationDeliveryExecutor")
    private final Executor notificationDeliveryExecutor;

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

        createIfAbsent(event, decision, DASHBOARD, FRAUD_ADMIN_DASHBOARD,
                dashboardSubject(decision), message, "SENT");

        if (BLOCK.equals(decision) || REVIEW.equals(decision)) {
            // Dispatched to a bounded background pool instead of running inline: a slow
            // SMTP/SMS provider call must never block this Kafka listener thread, or every
            // fraud event behind this one in the topic gets delayed waiting for it.
            notificationDeliveryExecutor.execute(() -> dispatchAlerts(event, decision, data));
        }
    }

    private void dispatchAlerts(FraudEvent event, String decision, Map<String, Object> data) {
        try {
            sendConfiguredAdminEmails(event, decision, data);
        } catch (Exception ex) {
            log.error("Unexpected failure dispatching email alerts transactionId={}", event.transactionId(), ex);
        }
        try {
            sendConfiguredAdminSms(event, decision);
        } catch (Exception ex) {
            log.error("Unexpected failure dispatching SMS alerts transactionId={}", event.transactionId(), ex);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getNotifications(
            UUID transactionId,
            String notificationType,
            String fraudDecision,
            String notificationStatus,
            String alertStatus,
            String recipient,
            Pageable pageable
    ) {
        Specification<Notification> specification = Specification.where(null);
        if (transactionId != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("transactionId"), transactionId));
        }
        if (StringUtils.hasText(notificationType)) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("notificationType"), notificationType));
        }
        if (StringUtils.hasText(fraudDecision)) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("fraudDecision"), fraudDecision));
        }
        if (StringUtils.hasText(notificationStatus)) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("notificationStatus"), notificationStatus));
        }
        if (StringUtils.hasText(alertStatus)) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("alertStatus"), alertStatus));
        }
        if (StringUtils.hasText(recipient)) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("recipient"), recipient));
        }
        return notificationRepository.findAll(specification, pageable).map(this::toResponse);
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
        return getNotifications(transactionId, null, null, null, null, null, pageable);
    }

    @Override
    @Transactional
    public NotificationResponse updateAlertStatus(UUID notificationId, UpdateAlertStatusRequest request) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Notification not found: " + notificationId));
        if (!DASHBOARD.equals(notification.getNotificationType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Alert status can only be updated for DASHBOARD notifications");
        }

        notification.setAlertStatus(request.alertStatus().trim().toUpperCase());
        notification.setUpdatedAt(LocalDateTime.now());
        return toResponse(notificationRepository.save(notification));
    }

    private void createIfAbsent(
            FraudEvent event, String decision, String type, String recipient, String subject, String message, String deliveryStatus
    ) {
        if (notificationRepository.existsByTransactionIdAndNotificationTypeAndFraudDecisionAndRecipient(
                event.transactionId(), type, decision, recipient)) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        Notification notification = new Notification();
        notification.setTransactionId(event.transactionId());
        notification.setNotificationType(type);
        notification.setRecipient(StringUtils.hasText(recipient) ? recipient : "SYSTEM");
        notification.setSubject(subject);
        notification.setMessage(message);
        notification.setFraudDecision(decision);
        notification.setRiskScore(event.totalRiskScore() == null ? 0 : event.totalRiskScore());
        notification.setNotificationStatus(deliveryStatus);
        notification.setAlertStatus(initialAlertStatus(decision));
        notification.setRetryCount(0);
        notification.setStatus(true);
        notification.setCreatedBy(SYSTEM_USER);
        notification.setCreatedDate(now);
        notification.setUpdatedAt(now);
        notificationRepository.save(notification);
        log.info("Notification recorded transactionId={}, type={}, status={}",
                event.transactionId(), type, deliveryStatus);
    }

    private void sendConfiguredAdminEmails(FraudEvent event, String decision, Map<String, Object> data) {
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
                .forEach(recipient -> sendEmail(
                        event,
                        decision,
                        recipient,
                        subject,
                        message
                ));
    }

    private void sendEmail(FraudEvent event, String decision, String recipient, String subject, String message) {
        if (notificationRepository.existsByTransactionIdAndNotificationTypeAndFraudDecisionAndRecipient(
                event.transactionId(), EMAIL, decision, recipient)) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        Notification notification = new Notification();
        notification.setTransactionId(event.transactionId());
        notification.setNotificationType(EMAIL);
        notification.setRecipient(recipient);
        notification.setSubject(subject);
        notification.setMessage(message);
        notification.setFraudDecision(decision);
        notification.setRiskScore(event.totalRiskScore() == null ? 0 : event.totalRiskScore());
        notification.setNotificationStatus(PENDING);
        notification.setAlertStatus(initialAlertStatus(decision));
        notification.setRetryCount(0);
        notification.setStatus(true);
        notification.setCreatedBy(SYSTEM_USER);
        notification.setCreatedDate(now);
        notification.setUpdatedAt(now);
        notificationRepository.saveAndFlush(notification);

        try {
            emailSenderService.send(recipient, notification.getSubject(), message);
            notification.setNotificationStatus(SENT);
            notification.setUpdatedAt(LocalDateTime.now());
            notificationRepository.save(notification);
            log.info("Email alert sent transactionId={}, recipient={}", event.transactionId(), recipient);
        } catch (Exception ex) {
            notification.setNotificationStatus(FAILED);
            notification.setFailureReason(truncateFailureReason(ex.getMessage()));
            notification.setUpdatedAt(LocalDateTime.now());
            notificationRepository.save(notification);
            log.error("Email alert failed transactionId={}, recipient={}", event.transactionId(), recipient, ex);
            scheduleRetry(notification.getId());
        }
    }

    private void sendConfiguredAdminSms(FraudEvent event, String decision) {
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
                .forEach(recipient -> sendSms(event, decision, recipient, message, templateId));
    }

    private void sendSms(FraudEvent event, String decision, String recipient, String message, String templateId) {
        if (notificationRepository.existsByTransactionIdAndNotificationTypeAndFraudDecisionAndRecipient(
                event.transactionId(), SMS, decision, recipient)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        Notification notification = new Notification();
        notification.setTransactionId(event.transactionId());
        notification.setNotificationType(SMS);
        notification.setRecipient(recipient);
        notification.setSubject("SMS " + decision + " fraud alert");
        notification.setMessage(message);
        notification.setFraudDecision(decision);
        notification.setRiskScore(event.totalRiskScore() == null ? 0 : event.totalRiskScore());
        notification.setNotificationStatus(PENDING);
        notification.setAlertStatus(initialAlertStatus(decision));
        notification.setRetryCount(0);
        notification.setStatus(true);
        notification.setCreatedBy(SYSTEM_USER);
        notification.setCreatedDate(now);
        notification.setUpdatedAt(now);
        notificationRepository.saveAndFlush(notification);
        try {
            smsSenderService.send(recipient, message, templateId, "FRMS-" + decision + "-" + event.transactionId());
            notification.setNotificationStatus(SENT);
        } catch (Exception ex) {
            notification.setNotificationStatus(FAILED);
            notification.setFailureReason(truncateFailureReason(ex.getMessage()));
            log.error("SMS alert failed transactionId={}, recipient={}", event.transactionId(), recipient, ex);
            scheduleRetry(notification.getId());
        }
        notification.setUpdatedAt(LocalDateTime.now());
        notificationRepository.save(notification);
    }

    /**
     * Quick retry delays are 2s, 5s and 30s. This is executed outside the Kafka
     * consumer thread, therefore a failed provider call never delays a fraud decision.
     */
    private void scheduleRetry(UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId).orElse(null);
        if (notification == null || notification.getRetryCount() == null
                || notification.getRetryCount() >= MAX_RETRY_ATTEMPTS) {
            return;
        }
        long delayMillis = switch (notification.getRetryCount()) {
            case 0 -> 2_000L;
            case 1 -> 5_000L;
            case 2 -> 30_000L;
            default -> -1L;
        };
        if (delayMillis > 0) {
            notificationRetryTaskScheduler.schedule(
                    () -> retryDelivery(notificationId), Instant.now().plusMillis(delayMillis));
        }
    }

    private void retryDelivery(UUID notificationId) {
        // Atomic claim: this UPDATE only matches (and only affects a row) if the
        // notification is still FAILED and under the retry limit at this exact moment.
        // If scheduleRetry's own timer AND the recoverFailedDeliveries sweep both try to
        // retry the same notification, only one of these calls can see notification_status
        // still equal to 'FAILED' and flip it to PENDING — the other gets 0 rows affected
        // and backs off, so the alert is never sent twice. No schema change needed: this
        // reuses the existing notification_status/retry_count columns as the claim signal.
        int claimed = notificationRepository.claimForRetry(notificationId, MAX_RETRY_ATTEMPTS, LocalDateTime.now());
        if (claimed == 0) {
            log.info("Notification not eligible or already claimed by another retry attempt, notificationId={}", notificationId);
            return;
        }

        Notification notification = notificationRepository.findById(notificationId).orElse(null);
        if (notification == null) {
            return;
        }

        try {
            if (EMAIL.equals(notification.getNotificationType())) {
                emailSenderService.send(notification.getRecipient(), notification.getSubject(), notification.getMessage());
            } else if (SMS.equals(notification.getNotificationType())) {
                String templateId = REVIEW.equals(notification.getFraudDecision())
                        ? reviewSmsTemplateId : blockSmsTemplateId;
                smsSenderService.send(notification.getRecipient(), notification.getMessage(), templateId,
                        "FRMS-" + notification.getFraudDecision() + "-RETRY-" + notification.getId());
            } else {
                return;
            }
            notification.setNotificationStatus(SENT);
            notification.setFailureReason(null);
            log.info("Notification retry succeeded notificationId={}, retryCount={}",
                    notification.getId(), notification.getRetryCount());
        } catch (Exception ex) {
            notification.setNotificationStatus(FAILED);
            notification.setFailureReason(truncateFailureReason(ex.getMessage()));
            log.warn("Notification retry failed notificationId={}, retryCount={}",
                    notification.getId(), notification.getRetryCount());
        }
        notification.setUpdatedAt(LocalDateTime.now());
        notificationRepository.save(notification);

        if (FAILED.equals(notification.getNotificationStatus())) {
            if (notification.getRetryCount() != null && notification.getRetryCount() >= MAX_RETRY_ATTEMPTS) {
                // Every attempt is exhausted. Previously this fell through to scheduleRetry(),
                // which silently no-ops once retryCount >= MAX_RETRY_ATTEMPTS — the fraud alert
                // was then just left as FAILED forever with nobody told. Surface it instead.
                handlePermanentFailure(notification);
            } else {
                scheduleRetry(notification.getId());
            }
        }
    }

    /**
     * Called once, right when the final retry attempt fails. Nothing about the row
     * itself changes (no new status, no schema change) — notification_status stays
     * FAILED and retry_count stays at MAX_RETRY_ATTEMPTS, which is already a reliable,
     * queryable signal for "permanently failed" via the existing GET /notifications
     * endpoint (notificationStatus=FAILED, retryCount=MAX_RETRY_ATTEMPTS).
     */
    private void handlePermanentFailure(Notification notification) {
        log.error("PERMANENTLY FAILED: fraud alert could not be delivered after {} attempts. "
                        + "notificationId={}, transactionId={}, type={}, recipient={}, decision={}, failureReason={}",
                notification.getRetryCount(), notification.getId(), notification.getTransactionId(),
                notification.getNotificationType(), notification.getRecipient(), notification.getFraudDecision(),
                notification.getFailureReason());
    }

    /** Recover retryable failures left behind if the service restarted mid-retry. */
    @Scheduled(initialDelay = 60_000L, fixedDelay = 60_000L)
    public void recoverFailedDeliveries() {
        List<Notification> failedNotifications = notificationRepository
                .findTop100ByNotificationStatusAndRetryCountLessThanOrderByUpdatedAtAsc(FAILED, MAX_RETRY_ATTEMPTS);
        failedNotifications.forEach(notification -> notificationRetryTaskScheduler.schedule(
                () -> retryDelivery(notification.getId()), Instant.now()));
    }

    private String truncateFailureReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "Email delivery failed without an error message";
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

    private String initialAlertStatus(String decision) {
        return "ALLOW".equals(decision) ? "RESOLVED" : "PENDING";
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

    private String buildSmsMessage(FraudEvent event, String decision) {
        int riskScore = event.totalRiskScore() == null ? 0 : event.totalRiskScore();
        if (REVIEW.equals(decision)) {
            return "Secure Edge: Fraud review required for transaction " + event.transactionId()
                    + ". Risk score: " + riskScore + ". Please review in FRMS.";
        }
        return "Secure Edge: High-risk transaction " + event.transactionId()
                + " has been blocked. Risk score: " + riskScore + ". Please review in FRMS.";
    }

    /**
     * Temporary code-based templates. These can later be moved to a managed
     * notification-template table without changing the delivery flow.
     */
    private String buildEmailMessage(FraudEvent event, String decision, Map<String, Object> data) {
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
        String heading = REVIEW.equals(decision)
                ? "A transaction requires manual fraud review."
                : "A high-risk transaction has been blocked.";
        String action = REVIEW.equals(decision)
                ? "Review this transaction and update the alert status."
                : "Verify the blocked transaction and take any required follow-up action.";

        return "Dear Admin,"
                + "\n\n" + heading
                + "\n\nTransaction ID: " + event.transactionId()
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
                .replace("{{amount}}", value(data, "amount", "N/A"))
                .replace("{{currency}}", value(data, "currency", ""))
                .replace("{{channel}}", value(data, "channel", "N/A"))
                .replace("{{location}}", location)
                .replace("{{decision}}", decision)
                .replace("{{riskScore}}", String.valueOf(event.totalRiskScore() == null ? 0 : event.totalRiskScore()))
                .replace("{{decisionReason}}", reason)
                .replace("{{triggeredRules}}", rules);
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
                notification.getId(), notification.getTransactionId(), notification.getNotificationType(),
                notification.getRecipient(), notification.getSubject(), notification.getMessage(),
                notification.getFraudDecision(), notification.getRiskScore(), notification.getNotificationStatus(),
                notification.getAlertStatus(),
                notification.getRetryCount(),
                notification.getFailureReason(), notification.getStatus(), notification.getCreatedDate(), notification.getUpdatedAt()
        );
    }
}
