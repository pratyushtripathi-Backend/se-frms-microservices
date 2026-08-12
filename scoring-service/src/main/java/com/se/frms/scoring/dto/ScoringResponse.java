package com.se.frms.scoring.dto;
import java.util.UUID;
public record ScoringResponse(UUID transactionId, Integer totalRiskScore, String finalDecision, String reason) {}
