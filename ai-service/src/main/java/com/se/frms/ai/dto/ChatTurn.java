package com.se.frms.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** One prior turn of a chat conversation, sent back by the caller as conversation history. */
public record ChatTurn(
        @NotBlank @Pattern(regexp = "user|assistant") String role,
        @NotBlank @Size(max = 4000) String content
) {
}
