package com.se.frms.scoring.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scoring request from Fraud Engine.
 *
 * Optional fields (used by RulesVersionCache, so the active rules are not sent with
 * every transaction):
 *  - rulesVersion + rulesBaseCount on a full request: the first rulesBaseCount entries
 *    of activeRules are the active rules of rulesVersion - stored for later requests;
 *  - rulesVersion + rulesCached = true: activeRules holds only this transaction's extra
 *    (blacklist) rules; the stored rules of rulesVersion are put in front of them.
 * Without these fields the request works exactly as before.
 */
public record ScoringRequest(
        @NotNull(message = "transactionId is required") UUID transactionId,
        @NotNull(message = "activeRules are required") List<@Valid RuleEvaluationRequest> activeRules,
        @NotEmpty(message = "transactionData is required") Map<String, Object> transactionData,
        String rulesVersion,
        Integer rulesBaseCount,
        Boolean rulesCached
) {

    public ScoringRequest(UUID transactionId, List<RuleEvaluationRequest> activeRules, Map<String, Object> transactionData) {
        this(transactionId, activeRules, transactionData, null, null, null);
    }

    /** Same rule as before (activeRules must not be empty), except for a compact request. */
    @AssertTrue(message = "activeRules are required")
    public boolean isActiveRulesProvided() {
        return isCompact() || (activeRules != null && !activeRules.isEmpty());
    }

    public boolean isCompact() {
        return Boolean.TRUE.equals(rulesCached) && rulesVersion != null;
    }
}
