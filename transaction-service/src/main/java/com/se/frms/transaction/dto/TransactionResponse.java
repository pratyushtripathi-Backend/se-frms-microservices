package com.se.frms.transaction.dto;

import java.util.UUID;

public record TransactionResponse(
        UUID transactionId,
        String status,
        String finalDecision,
        Integer riskScore,
        String remarks

) {


}

