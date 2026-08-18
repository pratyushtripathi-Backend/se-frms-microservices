package com.se.frms.rulecache.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record ActiveRuleResponse(
        UUID id,
        Integer ruleId,
        Integer categoryId,
        String ruleCode,
        String ruleName,
        String ruleDescription,
        String ruleExpression,
        String categoryName,
        Integer ruleScore,
        Boolean status,
        String createdBy,
        LocalDateTime createdDate,
        LocalDateTime updatedAt
) {
}
