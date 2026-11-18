package com.se.frms.ai.dto;

import java.util.Map;

public record SummarizeTrendsRequest(
        Map<String, Object> fraudTrend,
        Map<String, Object> rulePerformance
) {
}
