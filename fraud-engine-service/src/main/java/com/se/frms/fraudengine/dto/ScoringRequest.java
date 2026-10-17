package com.se.frms.fraudengine.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request to Scoring Service.
 *
 * rulesVersion / rulesBaseCount / rulesCached let Scoring Service keep the active rule
 * list instead of receiving it with every transaction (see ScoringClient):
 *  - full request:    activeRules = active rules + matched blacklist rules (as before),
 *                     rulesVersion = version of the active rules,
 *                     rulesBaseCount = how many leading entries are those active rules;
 *  - compact request: activeRules = matched blacklist rules only, rulesCached = true -
 *                     Scoring Service puts its stored rules for rulesVersion in front.
 * A Scoring Service that does not know these fields ignores them (full request only).
 */
public record ScoringRequest(
        UUID transactionId,
        List<ActiveRuleResponse> activeRules,
        Map<String, Object> transactionData,
        String rulesVersion,
        Integer rulesBaseCount,
        Boolean rulesCached
) {

    public ScoringRequest(UUID transactionId, List<ActiveRuleResponse> activeRules, Map<String, Object> transactionData) {
        this(transactionId, activeRules, transactionData, null, null, null);
    }
}
