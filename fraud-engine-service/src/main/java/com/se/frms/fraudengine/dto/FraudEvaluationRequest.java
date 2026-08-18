package com.se.frms.fraudengine.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

public record FraudEvaluationRequest(
        @NotNull(message = "transactionId is required") UUID transactionId,
        @NotEmpty(message = "transactionData is required") Map<String, Object> transactionData
) {
}
