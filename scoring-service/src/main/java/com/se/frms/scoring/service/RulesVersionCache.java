package com.se.frms.scoring.service;

import com.se.frms.scoring.dto.RuleEvaluationRequest;
import com.se.frms.scoring.dto.ScoringRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Keeps the active rule lists Fraud Engine has sent, by version (a fingerprint of the
 * rules' exact content computed by Fraud Engine), so Fraud Engine does not have to send
 * the same rules with every transaction. Only the last few versions are kept.
 *
 * resolve() turns any request into the full request it stands for:
 *  - full request with a version: stores its leading active rules, returns it unchanged;
 *  - compact request: stored rules + the request's extra rules, in that order (exactly
 *    the list Fraud Engine would have sent); empty if the version is not stored here,
 *    in which case the controller answers 409 and Fraud Engine sends the full request.
 */
@Component
public class RulesVersionCache {

    private static final int MAX_VERSIONS = 8;

    private final Map<String, List<RuleEvaluationRequest>> rulesByVersion =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<RuleEvaluationRequest>> eldest) {
                    return size() > MAX_VERSIONS;
                }
            };

    public Optional<ScoringRequest> resolve(ScoringRequest request) {
        if (request.isCompact()) {
            List<RuleEvaluationRequest> stored = get(request.rulesVersion());
            if (stored == null) {
                return Optional.empty();
            }
            List<RuleEvaluationRequest> extra = request.activeRules() != null ? request.activeRules() : List.of();
            List<RuleEvaluationRequest> rules = new ArrayList<>(stored.size() + extra.size());
            rules.addAll(stored);
            rules.addAll(extra);
            return Optional.of(new ScoringRequest(request.transactionId(), rules, request.transactionData(),
                    request.rulesVersion(), stored.size(), null));
        }
        Integer baseCount = request.rulesBaseCount();
        if (request.rulesVersion() != null && baseCount != null && baseCount > 0
                && request.activeRules() != null && baseCount <= request.activeRules().size()
                && get(request.rulesVersion()) == null) {
            put(request.rulesVersion(), List.copyOf(request.activeRules().subList(0, baseCount)));
        }
        return Optional.of(request);
    }

    /** True if this version's rules are stored (told to Fraud Engine in a response header). */
    public boolean has(String version) {
        return version != null && get(version) != null;
    }

    private synchronized List<RuleEvaluationRequest> get(String version) {
        return rulesByVersion.get(version);
    }

    private synchronized void put(String version, List<RuleEvaluationRequest> rules) {
        rulesByVersion.put(version, rules);
    }
}
