package com.se.frms.rulecache.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record DecisionPolicyCacheResponse(
        UUID id,
        Integer policyId,
        String description,
        Integer allowMinScore,
        Integer allowMaxScore,
        Integer reviewMinScore,
        Integer reviewMaxScore,
        Integer blockMinScore,
        Integer blockMaxScore,
        Boolean status,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
