package com.se.frms.notification.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record NotificationListResponse(
        UUID id,
        UUID transactionId,
        String notificationType,
        String recipient,
        String subject,
        String fraudDecision,
        Integer riskScore,
        String notificationStatus,
        String failureReason,
        String createdBy,
        LocalDateTime createdDate,
        LocalDateTime updatedAt
) {
}
