package com.se.frms.notification.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record NotificationTemplateResponse(
        UUID id,
        String templateCode,
        String notificationType,
        String fraudDecision,
        String subjectTemplate,
        String bodyTemplate,
        Boolean status,
        String createdBy,
        LocalDateTime createdDate,
        LocalDateTime updatedAt
) {
}
