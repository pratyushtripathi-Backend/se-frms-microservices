package com.se.frms.decision.cache;

import com.se.frms.decision.client.RuleCacheDecisionPolicyClient;
import com.se.frms.decision.dto.DecisionPolicyResponse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DecisionPolicyCache {

    private final RuleCacheDecisionPolicyClient ruleCacheDecisionPolicyClient;
    private volatile DecisionPolicyResponse activePolicy;

    @PostConstruct
    public void warmUp() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${decision.policy.refresh-interval-ms:30000}")
    public void refresh() {
        try {
            DecisionPolicyResponse refreshedPolicy =
                    ruleCacheDecisionPolicyClient.getActiveDecisionPolicy();
            if (refreshedPolicy != null && Boolean.TRUE.equals(refreshedPolicy.status())) {
                activePolicy = refreshedPolicy;
                log.info(
                        "Decision policy cache refreshed policyId={}, description={}",
                        refreshedPolicy.policyId(),
                        refreshedPolicy.description()
                );
            }
        } catch (RuntimeException ex) {
            log.warn(
                    "Decision policy cache refresh failed. Existing policyId={}, reason={}",
                    activePolicy != null ? activePolicy.policyId() : null,
                    ex.getMessage()
            );
        }
    }

    public DecisionPolicyResponse getActivePolicy() {
        return activePolicy;
    }
}
