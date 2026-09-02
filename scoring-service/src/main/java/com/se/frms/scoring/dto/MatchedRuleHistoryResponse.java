package com.se.frms.scoring.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record MatchedRuleHistoryResponse(
        UUID id,
        UUID scoringId,
        UUID transactionId,
        Integer ruleId,
        String ruleCode,
        String ruleName,
        String ruleExpression,
        Integer ruleScore,
        Integer calculatedScore,
        Boolean status,
        String createdBy,
        LocalDateTime createdDate,
        LocalDateTime updatedAt
) {
}
