package com.se.frms.ai.dto;

import java.util.Map;

/**
 * Prompt-based score for evaluation only - not the trained gradient-boosted model the proposal
 * specifies for production blending. Disabled by default, see ai.features.risk-score.enabled.
 */
public record RiskScoreRequest(Map<String, Object> features) {
}
