package com.se.frms.fraudengine.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record ActiveBlacklistResponse(
        UUID id,
        Integer blacklistId,
        String type,
        String value,
        Boolean status,
        String createdBy,
        LocalDateTime createdDate,
        LocalDateTime updatedAt
) {
}
