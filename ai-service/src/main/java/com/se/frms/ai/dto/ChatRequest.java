package com.se.frms.ai.dto;

import jakarta.validation.constraints.NotBlank;

/** Single-turn chat - no tool-calling against other services, no conversation history. */
public record ChatRequest(@NotBlank String message) {
}
