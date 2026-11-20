package com.se.frms.analytics.dto;

import java.util.Map;

/**
 * Outbound request body to ai-service's POST /api/v1/ai/summarize-trends,
 * matching that endpoint's SummarizeTrendsRequest shape field-for-field.
 */
public record AiTrendSummaryRequest(
        Map<String, Object> fraudTrend,
        Map<String, Object> rulePerformance
) {
}
