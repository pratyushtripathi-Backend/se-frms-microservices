package com.se.frms.notification.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * One row per transaction + fraud decision (unique). Channel-wise delivery
 * state lives in notification_details (JSONB); see NotificationDetails.
 */
@Getter
@Setter
@Entity
@Table(name = "se_frms_notification")
public class Notification implements Persistable<UUID> {
    /**
     * Assigned in code (UUID.randomUUID()) before the row is saved, so the
     * dashboard alert can be pushed over WebSocket with its final id BEFORE the
     * INSERT - see NotificationServiceImpl.findOrCreateNotification().
     */
    @Id
    private UUID id;

    /**
     * Because the id is assigned in code, Spring Data cannot use "id == null" to
     * tell a new row from an existing one. Without this flag save() would run a
     * SELECT before every INSERT. true until the row is saved or loaded.
     */
    @Transient
    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean newRow = true;

    @Override
    @JsonIgnore
    public boolean isNew() {
        return newRow;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newRow = false;
    }

    private UUID transactionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "notification_details", columnDefinition = "jsonb")
    private NotificationDetails notificationDetails = new NotificationDetails();

    /** Dashboard alert subject / message. Email and SMS text is inside notificationDetails. */
    private String subject;
    @Lob
    @Column(columnDefinition = "TEXT")
    private String message;
    private String fraudDecision;
    private Integer riskScore;
    private Boolean status;
    /**
     * Shared read state of the dashboard alert (one flag for all admins). Stored
     * here, not in the browser, so the unread bell count survives logout / cleared
     * storage. Only ever changed by NotificationRepository.markAllReadUpTo() - a
     * column-only UPDATE, so it never touches notification_details.
     */
    @Column(name = "is_read", nullable = false)
    private Boolean read = false;
    private LocalDateTime readAt;
    private String createdBy;
    private LocalDateTime createdDate;
    private LocalDateTime updatedAt;

    /*
     * ---- JSON stored in notification_details ----
     * {
     *   "DASHBOARD": { "status": "SENT" },
     *   "EMAIL": { "subject": "...", "message": "...",
     *              "recipients": { "admin@x.com": { "status": "SENT", "retryCount": 0 } } },
     *   "SMS":   { "message": "...",
     *              "recipients": { "919876543210": { "status": "DELIVERED", "messageId": "M1", "retryCount": 0 } } }
     * }
     * IMPORTANT: after the row is first inserted, never update notification_details by
     * saving the entity (that rewrites the whole JSON and can overwrite a concurrent
     * retry / delivery-status update). Use the jsonb_set update queries in
     * NotificationRepository, which change only one entry.
     */

    @Data
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NotificationDetails {

        @JsonProperty("DASHBOARD")
        private DashboardDelivery dashboard;

        @JsonProperty("EMAIL")
        private ChannelDelivery email;

        @JsonProperty("SMS")
        private ChannelDelivery sms;

        @JsonIgnore
        public ChannelDelivery channelFor(String channel) {
            if ("EMAIL".equals(channel)) {
                return email;
            }
            if ("SMS".equals(channel)) {
                return sms;
            }
            return null;
        }

        @JsonIgnore
        public RecipientDelivery recipientFor(String channel, String recipient) {
            ChannelDelivery channelDelivery = channelFor(channel);
            return channelDelivery == null || channelDelivery.getRecipients() == null
                    ? null
                    : channelDelivery.getRecipients().get(recipient);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DashboardDelivery {
        private String status;
    }

    @Data
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChannelDelivery {
        private String subject;
        private String message;
        private Map<String, RecipientDelivery> recipients = new LinkedHashMap<>();
    }

    @Data
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RecipientDelivery {
        private String status;
        private String messageId;
        private Integer retryCount;
        private String failureReason;
    }
}
