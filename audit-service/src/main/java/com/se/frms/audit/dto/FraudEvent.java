package com.se.frms.audit.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record FraudEvent(
        UUID transactionId,
        UUID scoringId,
        UUID decisionId,
        Integer totalRiskScore,
        String fraudDecision,
        Map<String, Object> transactionData,
        Map<String, Object> triggeredRules,
        Instant occurredAt
) {
}
