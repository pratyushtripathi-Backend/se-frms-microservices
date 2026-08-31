package com.se.frms.audit.dto;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record AuditLogResponse(
        UUID id,
        UUID transactionId,
        String serviceName,
        String eventType,
        UUID referenceId,
        Map<String, Object> eventDetails,
        String performedBy,
        Boolean status,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
