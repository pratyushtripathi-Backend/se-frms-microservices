package com.se.frms.fraudengine.dto;

import java.util.Map;
import java.util.UUID;

public record DecisionRequest(
        UUID transactionId,
        UUID scoringId,
        Integer totalRiskScore,
        Map<String, Object> transactionData
) {
}
