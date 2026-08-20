package com.se.frms.fraudengine.client;

import com.se.frms.fraudengine.dto.ActiveRuleResponse;
import com.se.frms.fraudengine.exception.ExternalServiceException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@RequiredArgsConstructor
@Slf4j
public class RuleCacheClient {

    @Qualifier("restClientBuilder")
    private final RestClient.Builder restClientBuilder;
    @Qualifier("directRestClientBuilder")
    private final RestClient.Builder directRestClientBuilder;

    @Value("${frms.rule-cache.base-url}")
    private String ruleCacheBaseUrl;

    @Value("${frms.rule-cache.active-rules-path}")
    private String activeRulesPath;

    public List<ActiveRuleResponse> getActiveRules() {
        log.info("Fetching active rules from Rule Cache");
        try {
            List<ActiveRuleResponse> rules = restClientBuilder().build()
                    .get()
                    .uri(ruleCacheBaseUrl + activeRulesPath)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            log.info("Fetched {} active rules from Rule Cache", rules != null ? rules.size() : 0);
            return rules != null ? rules : List.of();
        } catch (RestClientException ex) {
            log.error("Rule Cache call failed", ex);
            throw new ExternalServiceException("Unable to fetch active rules from Rule Cache", ex);
        }
    }

    private RestClient.Builder restClientBuilder() {
        return isLocalUrl(ruleCacheBaseUrl) ? directRestClientBuilder : restClientBuilder;
    }

    private boolean isLocalUrl(String url) {
        return url != null && (url.contains("localhost") || url.contains("127.0.0.1"));
    }
}
