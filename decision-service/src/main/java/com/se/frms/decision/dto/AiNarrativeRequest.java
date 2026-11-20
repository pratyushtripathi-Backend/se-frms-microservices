package com.se.frms.decision.dto;

import java.util.Map;
import java.util.UUID;

/**
 * Outbound request body to ai-service's POST /api/v1/ai/explain-decision,
 * matching that endpoint's ExplainDecisionRequest shape field-for-field.
 */
public record AiNarrativeRequest(
        UUID decisionId,
        Map<String, Object> matchedRules,
        Map<String, Object> transactionDetail,
        Integer score,
        String finalDecision
) {
}
