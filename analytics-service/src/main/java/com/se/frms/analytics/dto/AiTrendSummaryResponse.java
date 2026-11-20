package com.se.frms.analytics.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Local projection of ai-service's SummarizeTrendsResponse - only the field
 * this service actually needs. @JsonIgnoreProperties keeps this safe if
 * ai-service's response grows more fields later.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiTrendSummaryResponse(String summary) {
}
