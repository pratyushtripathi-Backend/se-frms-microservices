package com.se.frms.notification.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateNotificationTemplateRequest(
        @NotBlank String subjectTemplate,
        @NotBlank String bodyTemplate
) {
}
