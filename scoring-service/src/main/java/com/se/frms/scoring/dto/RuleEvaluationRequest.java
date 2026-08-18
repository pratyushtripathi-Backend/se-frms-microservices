package com.se.frms.scoring.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record RuleEvaluationRequest(
        @NotNull(message = "ruleId is required") Integer ruleId,
        Integer categoryId,
        @NotBlank(message = "ruleCode is required") String ruleCode,
        @NotBlank(message = "ruleName is required") String ruleName,
        String ruleDescription,
        String ruleExpression,
        String categoryName,
        @NotNull(message = "ruleScore is required")
        @PositiveOrZero(message = "ruleScore must not be negative")
        Integer ruleScore,
        Boolean status
) {
}
