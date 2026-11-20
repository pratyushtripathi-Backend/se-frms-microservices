package com.se.frms.decision.client;

import com.se.frms.decision.dto.AiNarrativeRequest;
import com.se.frms.decision.dto.AiNarrativeResponse;
import com.se.frms.decision.dto.ScoringLookupResponse;
import com.se.frms.decision.exception.ExternalServiceException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Asks ai-service to write a plain-English narrative for one decision, for
 * the case-management view. Same direct-vs-load-balanced split as
 * ScoringLookupClient, but on its own RestClient.Builder pair
 * (directAiRestClientBuilder/loadBalancedAiRestClientBuilder) sized with a
 * longer read timeout - an LLM completion takes materially longer than the
 * scoring/transaction lookups' own quick Postgres reads.
 */
@Component
@Slf4j
public class AiNarrativeClient {

    private final RestClient.Builder directRestClientBuilder;
    private final RestClient.Builder loadBalancedRestClientBuilder;
    private volatile RestClient restClient;

    public AiNarrativeClient(
            @Qualifier("directAiRestClientBuilder") RestClient.Builder directRestClientBuilder,
            @Qualifier("loadBalancedAiRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder
    ) {
        this.directRestClientBuilder = directRestClientBuilder;
        this.loadBalancedRestClientBuilder = loadBalancedRestClientBuilder;
    }

    @Value("${frms.ai.base-url:http://localhost:8101}")
    private String aiBaseUrl;

    @Value("${frms.ai.path:/api/v1/ai}")
    private String aiPath;

    public String explainDecision(
            UUID decisionId,
            List<ScoringLookupResponse.MatchedRuleInfo> matchedRules,
            BigDecimal amount,
            String mode,
            Integer totalRiskScore,
            String finalDecision
    ) {
        Map<String, Object> matchedRulesPayload = new HashMap<>();
        matchedRulesPayload.put("rules", matchedRules);

        Map<String, Object> transactionDetail = new HashMap<>();
        transactionDetail.put("amount", amount);
        transactionDetail.put("mode", mode);

        AiNarrativeRequest request = new AiNarrativeRequest(
                decisionId, matchedRulesPayload, transactionDetail, totalRiskScore, finalDecision
        );
        try {
            AiNarrativeResponse response = restClient()
                    .post()
                    .uri(aiBaseUrl + aiPath + "/explain-decision")
                    .body(request)
                    .retrieve()
                    .body(AiNarrativeResponse.class);
            return response == null ? null : response.narrative();
        } catch (RestClientException ex) {
            log.warn("AI narrative lookup failed for decisionId={}", decisionId, ex);
            throw new ExternalServiceException("Unable to fetch AI narrative", ex);
        }
    }

    private RestClient restClient() {
        RestClient client = restClient;
        if (client == null) {
            client = restClientBuilder().build();
            restClient = client;
        }
        return client;
    }

    private RestClient.Builder restClientBuilder() {
        return isLocalUrl(aiBaseUrl) ? directRestClientBuilder : loadBalancedRestClientBuilder;
    }

    private boolean isLocalUrl(String url) {
        return url != null && (url.contains("localhost") || url.contains("127.0.0.1"));
    }
}
