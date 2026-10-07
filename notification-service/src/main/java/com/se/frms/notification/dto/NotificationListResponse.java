package com.se.frms.notification.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** One item per transaction in the notification list (same channels shape as NotificationResponse). */
public record NotificationListResponse(
        UUID id,
        UUID transactionId,
        String fraudDecision,
        Integer riskScore,
        String subject,
        List<NotificationResponse.Channel> channels,
        String createdBy,
        LocalDateTime createdDate,
        LocalDateTime updatedAt,
        Boolean read
) {
}
