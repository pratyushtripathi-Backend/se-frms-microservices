package com.se.frms.transaction.dto;
import java.util.UUID;
public record FraudEvaluationResponse(UUID transactionId, String finalDecision, Integer totalRiskScore, String decisionReason) {}
