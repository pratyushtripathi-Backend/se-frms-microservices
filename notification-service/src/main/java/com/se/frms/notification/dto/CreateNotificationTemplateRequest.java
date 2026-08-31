package com.se.frms.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateNotificationTemplateRequest(
        @NotBlank String fraudDecision,
        @NotBlank String subjectTemplate,
        @NotBlank String bodyTemplate,
        @NotNull Boolean status
) {
}
