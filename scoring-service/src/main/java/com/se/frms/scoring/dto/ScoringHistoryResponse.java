package com.se.frms.scoring.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record ScoringHistoryResponse(
        UUID id,
        UUID transactionId,
        Integer totalRiskScore,
        Boolean status,
        String createdBy,
        LocalDateTime createdDate,
        LocalDateTime updatedAt
) {
}
