package com.se.frms.analytics.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyTransactionVolumeResponse(
        LocalDate date,
        BigDecimal totalAmount
) {
}
