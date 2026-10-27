package com.se.frms.fraudengine.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

/**
 * correlationId is optional: used only by POST /api/v1/fraud-evaluations/batch so each
 * transaction's logs and fraud event keep its own request id (a single call carries it in
 * the X-Request-Id header instead).
 */
public record FraudEvaluationRequest(
        @NotNull(message = "transactionId is required") UUID transactionId,
        @NotEmpty(message = "transactionData is required") Map<String, Object> transactionData,
        String correlationId
) {

    public FraudEvaluationRequest(UUID transactionId, Map<String, Object> transactionData) {
        this(transactionId, transactionData, null);
    }
}
