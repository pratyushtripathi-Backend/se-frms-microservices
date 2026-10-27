package com.se.frms.transaction.dto;

import java.util.UUID;

/** One result of fraud-engine's POST /api/v1/fraud-evaluations/batch (same order as sent). */
public record FraudEvaluationBatchItem(UUID transactionId, int status, FraudEvaluationResponse response, String error) {
}
