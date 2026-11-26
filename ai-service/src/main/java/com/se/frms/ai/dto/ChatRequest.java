package com.se.frms.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Multi-turn chat - no tool-calling against other services. The caller (frontend) owns and
 * resends conversation state as {@code history} on every request; this service is stateless
 * between calls. {@code history} is capped at 20 turns and each turn's content at 4000
 * characters (see ChatTurn) to bound per-request cost; {@code message} is capped the same way.
 */
public record ChatRequest(
        @Valid @Size(max = 20) List<ChatTurn> history,
        @NotBlank @Size(max = 4000) String message
) {
}
