package com.se.frms.analytics.dto;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record FraudAnalyticsResponse(
        UUID id,
        UUID transactionId,
        UUID scoringId,
        UUID decisionId,
        Integer totalRiskScore,
        String fraudDecision,
        Map<String, Object> triggeredRules,
        Map<String, Object> transactionData,
        Boolean status,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
