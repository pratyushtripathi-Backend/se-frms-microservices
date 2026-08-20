package com.se.frms.decision.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.Map;
import java.util.UUID;

public record DecisionRequest(
        @NotNull(message = "transactionId is required") UUID transactionId,
        @NotNull(message = "scoringId is required") UUID scoringId,
        @NotNull(message = "totalRiskScore is required")
        @PositiveOrZero(message = "totalRiskScore must not be negative")
        Integer totalRiskScore,
        Map<String, Object> transactionData
) {
}
