package com.se.frms.ai.dto;

import java.util.Map;

/** Needs a trained model that doesn't exist yet - disabled by default, see ai.features.risk-score.enabled. */
public record RiskScoreRequest(Map<String, Object> features) {
}
