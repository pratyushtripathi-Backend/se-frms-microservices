package com.se.frms.decision.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One row of the case-management table: this decision's own fields, merged
 * with amount/mode from transaction-service and matched-rule detail from
 * scoring-service.
 */
public record CaseResponse(
        UUID decisionId,
        UUID transactionId,
        BigDecimal amount,
        String mode,
        Integer totalRiskScore,
        List<ScoringLookupResponse.MatchedRuleInfo> matchedRules,
        String finalDecision,
        String decisionReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
