package com.se.frms.scoring.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ScoringRequest(
        @NotNull(message = "transactionId is required") UUID transactionId,
        @NotEmpty(message = "activeRules are required") List<@Valid RuleEvaluationRequest> activeRules,
        @NotEmpty(message = "transactionData is required") Map<String, Object> transactionData
) {
}
