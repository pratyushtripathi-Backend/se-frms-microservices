package com.se.frms.decision.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Published to Kafka whenever an admin manually Allows/Blocks a case from
 * Case Management (DecisionServiceImpl.reviewDecision). This is the only
 * signal analytics-service gets that a decision changed after the original
 * fraud event - without it, analytics-service's own copy of the decision
 * stays on whatever verdict fraud-engine-service first published, and the
 * "Active Case" / reviewCount stat on the dashboard never reflects cases
 * that have since been resolved.
 */
public record DecisionReviewedEvent(
        UUID transactionId,
        UUID decisionId,
        String previousDecision,
        String finalDecision,
        Instant reviewedAt
) {
}
