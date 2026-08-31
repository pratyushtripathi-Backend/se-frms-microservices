package com.se.frms.notification.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record FraudEvent(
        UUID transactionId,
        UUID scoringId,
        UUID decisionId,
        Integer totalRiskScore,
        String fraudDecision,
        String decisionReason,
        Map<String, Object> transactionData,
        Map<String, Object> triggeredRules,
        Instant occurredAt
) {
}
