package com.se.frms.analytics.dto;

public record RulePerformanceResponse(
        String ruleCode,
        long triggerCount,
        long totalScore
) {
}
