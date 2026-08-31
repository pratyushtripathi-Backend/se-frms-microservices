package com.se.frms.analytics.dto;

public record DecisionCountResponse(
        String fraudDecision,
        long count
) {
}
