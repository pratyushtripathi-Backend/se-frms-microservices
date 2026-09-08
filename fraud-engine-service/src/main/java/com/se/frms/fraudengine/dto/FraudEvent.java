package com.se.frms.fraudengine.dto;

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
        Instant occurredAt,
        // Traces this event back to the originating transaction-service
        // request (X-Request-Id), so consumers can log/measure end-to-end
        // latency against the same ID used across every synchronous hop.
        String correlationId
) {
}
