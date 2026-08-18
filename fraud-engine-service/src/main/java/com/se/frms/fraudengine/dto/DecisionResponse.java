package com.se.frms.fraudengine.dto;

import java.util.UUID;

public record DecisionResponse(
        UUID transactionId,
        Integer totalRiskScore,
        String finalDecision,
        String reason
) {
}
