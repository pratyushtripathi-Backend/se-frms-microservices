package com.se.frms.scoring.dto;

public record RuleEvaluationResult(
        RuleEvaluationRequest rule,
        boolean matched,
        Integer calculatedScore
) {
}
