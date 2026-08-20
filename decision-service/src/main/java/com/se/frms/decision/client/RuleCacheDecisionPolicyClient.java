package com.se.frms.decision.client;

import com.se.frms.decision.dto.DecisionPolicyResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
@Slf4j
public class RuleCacheDecisionPolicyClient {

    private final RestClient.Builder restClientBuilder;

    @Value("${frms.rule-cache.base-url:http://localhost:8093}")
    private String ruleCacheBaseUrl;

    @Value("${frms.rule-cache.active-decision-policy-path:/api/v1/rules/decision-policy/active}")
    private String activeDecisionPolicyPath;

    public DecisionPolicyResponse getActiveDecisionPolicy() {
        log.info("Fetching active decision policy from Rule Cache");
        return restClientBuilder
                .build()
                .get()
                .uri(ruleCacheBaseUrl + activeDecisionPolicyPath)
                .retrieve()
                .body(DecisionPolicyResponse.class);
    }
}
