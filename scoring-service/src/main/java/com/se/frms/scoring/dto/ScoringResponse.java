package com.se.frms.scoring.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ScoringResponse(
        UUID scoringId,
        UUID transactionId,
        Integer totalRiskScore,
        List<MatchedRuleResponse> matchedRules,
        Map<String, Object> triggeredRules,
        // Evaluation-only AI shadow score - null until the async call completes (seconds after
        // this response, or any response that read this row before then, was returned).
        // Never used for ALLOW/REVIEW/BLOCK; see AiScoringAsyncService.
        Double aiRiskScore,
        Integer combinedRiskScore,
        String aiReasoning
) {
}
