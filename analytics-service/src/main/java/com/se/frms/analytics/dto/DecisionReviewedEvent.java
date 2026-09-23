package com.se.frms.analytics.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumed from decision-service whenever an admin manually Allows/Blocks a
 * case in Case Management. Mirrors decision-service's own
 * DecisionReviewedEvent field-for-field (Jackson matches by JSON field name,
 * not by shared type, so the two classes just need to agree on shape).
 */
public record DecisionReviewedEvent(
        UUID transactionId,
        UUID decisionId,
        String previousDecision,
        String finalDecision,
        Instant reviewedAt
) {
}
