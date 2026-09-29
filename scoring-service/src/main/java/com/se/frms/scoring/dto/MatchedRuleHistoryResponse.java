package com.se.frms.scoring.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record MatchedRuleHistoryResponse(
        UUID id,
        UUID scoringId,
        UUID transactionId,
        List<MatchedRuleResponse> matchedRules,
        Boolean status,
        String createdBy,
        LocalDateTime createdDate,
        LocalDateTime updatedAt
) {
}