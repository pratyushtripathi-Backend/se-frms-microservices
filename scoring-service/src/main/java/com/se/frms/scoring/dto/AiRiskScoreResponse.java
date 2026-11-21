package com.se.frms.scoring.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiRiskScoreResponse(Double score, String reasoning) {
}
