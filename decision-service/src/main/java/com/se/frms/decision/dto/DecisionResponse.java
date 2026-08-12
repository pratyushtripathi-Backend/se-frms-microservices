package com.se.frms.decision.dto;
import java.util.UUID;
public record DecisionResponse(UUID transactionId, Integer totalRiskScore, String finalDecision, String reason) {}
