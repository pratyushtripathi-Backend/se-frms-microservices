package com.se.frms.scoring.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ScoringResponse(
        UUID scoringId,
        UUID transactionId,
        Integer totalRiskScore,
        List<MatchedRuleResponse> matchedRules,
        Map<String, Object> triggeredRules
) {
}
