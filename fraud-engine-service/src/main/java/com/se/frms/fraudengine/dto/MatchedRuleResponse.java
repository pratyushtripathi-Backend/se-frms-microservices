package com.se.frms.fraudengine.dto;

public record MatchedRuleResponse(
        Integer ruleId,
        String ruleCode,
        String ruleName,
        Integer ruleScore,
        Integer calculatedScore
) {
}
