package com.se.frms.ai.dto;

import jakarta.validation.constraints.NotBlank;

/** Needs tool-calling against other services; no in-repo consumer yet - disabled by default, see ai.features.chat.enabled. */
public record ChatRequest(@NotBlank String message) {
}
