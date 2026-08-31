package com.se.frms.notification.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        UUID transactionId,
        String notificationType,
        String recipient,
        String subject,
        String message,
        String fraudDecision,
        Integer riskScore,
        String notificationStatus,
        String alertStatus,
        Integer retryCount,
        String failureReason,
        Boolean status,
        LocalDateTime createdDate,
        LocalDateTime updatedAt
) {
}
