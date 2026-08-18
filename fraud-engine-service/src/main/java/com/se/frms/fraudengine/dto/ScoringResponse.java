package com.se.frms.fraudengine.dto;

import java.util.Map;
import java.util.List;
import java.util.UUID;

public record ScoringResponse(
        UUID scoringId,
        UUID transactionId,
        Integer totalRiskScore,
        List<MatchedRuleResponse> matchedRules,
        Map<String, Object> triggeredRules
) {
}
