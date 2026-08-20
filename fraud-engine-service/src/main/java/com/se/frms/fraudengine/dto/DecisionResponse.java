package com.se.frms.fraudengine.dto;

import java.util.UUID;

public record DecisionResponse(
        UUID decisionId,
        UUID transactionId,
        UUID scoringId,
        Integer totalRiskScore,
        String finalDecision,
        String reason
) {
}
