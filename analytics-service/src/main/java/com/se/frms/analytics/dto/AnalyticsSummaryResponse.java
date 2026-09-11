package com.se.frms.analytics.dto;

import java.math.BigDecimal;

public record AnalyticsSummaryResponse(
        long totalTransactions,
        long allowCount,
        long reviewCount,
        long blockCount,
        double averageRiskScore,
        // fraudAlertCount / highRiskCount / blockedAmount added for the
        // dashboard's stat cards (StatCards.jsx already expected these
        // three fields - they just never existed on this response before).
        long fraudAlertCount,
        long highRiskCount,
        BigDecimal blockedAmount
) {
}
