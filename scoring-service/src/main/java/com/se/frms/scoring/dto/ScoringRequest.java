package com.se.frms.scoring.dto;
import java.util.Map;
import java.util.UUID;
public record ScoringRequest(UUID transactionId, Integer totalRiskScore, Map<String, Object> transactionData) {}
