package com.se.frms.transaction.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record TransactionDetailsResponse(
        UUID transactionId,
        String externalTransactionId,
        String ipAddress,
        BigDecimal latitude,
        BigDecimal longitude,
        String merchantId,
        String userId,
        String channel,
        String transactionType,
        String currency,
        BigDecimal amount,
        Boolean duplicateTransaction,
        UUID originalTransactionId,
        Map<String, Object> transactionData,
        String remarks,
        String status,
        String createdBy,
        LocalDateTime createdDate,
        LocalDateTime updatedAt
) {
}
