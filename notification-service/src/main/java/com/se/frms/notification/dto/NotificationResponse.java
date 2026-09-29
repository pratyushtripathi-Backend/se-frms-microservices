package com.se.frms.notification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Full notification of one transaction (detail API, dashboard feed, WebSocket push).
 * channels is built from notification_details: DASHBOARD, EMAIL, SMS (only the ones used).
 */
public record NotificationResponse(
        UUID id,
        UUID transactionId,
        String fraudDecision,
        Integer riskScore,
        String subject,
        String message,
        List<Channel> channels,
        Boolean status,
        String createdBy,
        LocalDateTime createdDate,
        LocalDateTime updatedAt
) {
    /**
     * One channel: DASHBOARD, EMAIL or SMS. subject is set for EMAIL, recipients
     * (admin emails / phone numbers) for EMAIL and SMS.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Channel(String type, String subject, List<String> recipients) {
    }
}
