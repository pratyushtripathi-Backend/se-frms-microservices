package com.se.frms.scoring.dto;

public record MatchedRuleResponse(
        String ruleCode,
        String ruleName,
        String ruleExpression,
        Integer ruleScore,
        Integer calculatedScore
) {
}