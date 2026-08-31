package com.se.frms.decision.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record DecisionResponse(
        UUID decisionId,
        UUID transactionId,
        UUID scoringId,
        Integer totalRiskScore,
        String finalDecision,
        String reason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
