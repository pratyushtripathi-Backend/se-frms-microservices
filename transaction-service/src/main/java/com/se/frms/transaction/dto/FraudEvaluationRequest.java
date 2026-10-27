package com.se.frms.transaction.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.UUID;

/**
 * correlationId is sent only in batch calls (POST /api/v1/fraud-evaluations/batch), so
 * each transaction keeps its own request id in fraud-engine's logs and fraud event; a
 * single call carries it in the X-Request-Id header, as before.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FraudEvaluationRequest(UUID transactionId, Map<String, Object> transactionData, String correlationId) {

    public FraudEvaluationRequest(UUID transactionId, Map<String, Object> transactionData) {
        this(transactionId, transactionData, null);
    }
}
