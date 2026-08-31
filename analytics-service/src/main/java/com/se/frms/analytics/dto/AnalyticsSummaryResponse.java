package com.se.frms.analytics.dto;

public record AnalyticsSummaryResponse(
        long totalTransactions,
        long allowCount,
        long reviewCount,
        long blockCount,
        double averageRiskScore
) {
}
