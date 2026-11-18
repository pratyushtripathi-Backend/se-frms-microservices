package com.se.frms.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

public record ExplainDecisionRequest(
        @NotNull UUID decisionId,
        Map<String, Object> matchedRules,
        Map<String, Object> transactionDetail,
        @NotNull Integer score,
        @NotBlank String finalDecision
) {
}
