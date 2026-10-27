package com.se.frms.fraudengine.dto;

import java.util.UUID;

/**
 * Result of one transaction in POST /api/v1/fraud-evaluations/batch, in the same position
 * as its request: status 200 with the same response as POST /api/v1/fraud-evaluations, or
 * the status and error text that call would have answered for this transaction alone.
 */
public record FraudEvaluationBatchItem(UUID transactionId, int status, FraudEvaluationResponse response, String error) {
}
