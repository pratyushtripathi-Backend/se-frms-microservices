package com.se.frms.analytics.dto;

public record FraudTrendResponse(String period, long fraudAlertCount, long blockedCount) {
}
