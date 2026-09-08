package com.se.frms.decision.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

/**
 * Local projection of scoring-service's ScoringResponse, used only to pull
 * matched-rule details into the case-management view. @JsonIgnoreProperties
 * makes this resilient to scoring-service adding fields later (it already
 * has, more than once) - only the fields this service actually needs are
 * declared here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScoringLookupResponse(
        UUID scoringId,
        UUID transactionId,
        Integer totalRiskScore,
        List<MatchedRuleInfo> matchedRules
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MatchedRuleInfo(
            Integer ruleId,
            String ruleCode,
            String ruleName,
            String ruleExpression,
            Integer ruleScore,
            Integer calculatedScore
    ) {
    }
}
