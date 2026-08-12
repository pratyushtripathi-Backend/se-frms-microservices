package com.se.frms.fraudengine.dto;
import java.util.Map;
import java.util.UUID;
public record FraudEvaluationRequest(UUID transactionId, Map<String, Object> transactionData) {}
