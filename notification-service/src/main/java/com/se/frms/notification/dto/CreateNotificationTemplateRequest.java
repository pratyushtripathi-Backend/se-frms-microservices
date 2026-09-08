package com.se.frms.notification.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateNotificationTemplateRequest(
        @NotBlank String fraudDecision,
        @NotBlank String subjectTemplate,
        @NotBlank String bodyTemplate,
        Boolean status
) {
}
