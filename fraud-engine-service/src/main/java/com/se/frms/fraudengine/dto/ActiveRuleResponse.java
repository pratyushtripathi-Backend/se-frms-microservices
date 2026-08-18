package com.se.frms.fraudengine.dto;

public record ActiveRuleResponse(
        Integer ruleId,
        Integer categoryId,
        String ruleCode,
        String ruleName,
        String ruleDescription,
        String ruleExpression,
        String categoryName,
        Integer ruleScore,
        Boolean status
) {
}
