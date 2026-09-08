package com.se.frms.decision.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Local projection of transaction-service's TransactionDetailsResponse,
 * used only to pull amount + mode into the case-management view.
 * @JsonIgnoreProperties keeps this safe if transaction-service's response
 * grows more fields later.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransactionLookupResponse(
        UUID transactionId,
        BigDecimal amount,
        String channel
) {
}
