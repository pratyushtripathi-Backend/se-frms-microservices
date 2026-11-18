package com.se.frms.ai.dto;

import jakarta.validation.constraints.NotBlank;

public record NlToRuleRequest(
        @NotBlank String description
) {
}
