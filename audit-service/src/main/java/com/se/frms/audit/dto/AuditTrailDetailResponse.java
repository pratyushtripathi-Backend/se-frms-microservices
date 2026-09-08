package com.se.frms.audit.dto;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Consolidated "one screen" view of a transaction's fraud-evaluation audit
 * trail: the audit summary plus the scoring, decision, triggered-rule, and
 * transaction data captured at evaluation time, alongside the raw event
 * payload for debugging.
 */
public record AuditTrailDetailResponse(
        UUID auditLogId,
        UUID transactionId,
        String serviceName,
        String eventType,
        UUID referenceId,
        String performedBy,
        Boolean status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        ScoringDetails scoring,
        DecisionDetails decision,
        Map<String, Object> triggeredRules,
        Map<String, Object> transactionData,
        Map<String, Object> rawEvent
) {
    public record ScoringDetails(
            UUID scoringId,
            Integer totalRiskScore
    ) {
    }

    public record DecisionDetails(
            UUID decisionId,
            String finalDecision
    ) {
    }
}
