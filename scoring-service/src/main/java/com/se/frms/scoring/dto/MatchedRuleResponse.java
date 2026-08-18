package com.se.frms.scoring.dto;

public record MatchedRuleResponse(
        Integer ruleId,
        String ruleCode,
        String ruleName,
        Integer ruleScore,
        Integer calculatedScore
) {
}
