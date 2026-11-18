package com.se.frms.ai.dto;

import jakarta.validation.constraints.NotBlank;

public record DraftTemplateRequest(
        @NotBlank String intent,
        @NotBlank String channel
) {
}
