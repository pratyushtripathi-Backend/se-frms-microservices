package com.se.frms.notification.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /api/v1/notification-templates/ai-draft, forwarded
 * as-is to ai-service's POST /api/v1/ai/draft-template.
 */
public record AiDraftTemplateRequest(
        @NotBlank String intent,
        @NotBlank String channel
) {
}
