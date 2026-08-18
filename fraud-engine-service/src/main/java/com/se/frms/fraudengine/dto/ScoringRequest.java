package com.se.frms.fraudengine.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ScoringRequest(
        UUID transactionId,
        List<ActiveRuleResponse> activeRules,
        Map<String, Object> transactionData
) {
}
