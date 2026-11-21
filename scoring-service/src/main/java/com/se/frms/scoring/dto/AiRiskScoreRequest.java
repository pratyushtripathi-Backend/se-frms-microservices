package com.se.frms.scoring.dto;

import java.util.Map;

public record AiRiskScoreRequest(Map<String, Object> features) {
}
