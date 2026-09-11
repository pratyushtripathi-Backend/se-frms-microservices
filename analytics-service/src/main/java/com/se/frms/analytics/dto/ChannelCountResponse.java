package com.se.frms.analytics.dto;

public record ChannelCountResponse(
        String channel,
        long transactionCount
) {
}
