package com.se.frms.decision.dto;
import java.util.Map;
import java.util.UUID;
public record DecisionRequest(UUID transactionId, Integer totalRiskScore, Map<String, Object> transactionData) {}
