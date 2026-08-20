package com.se.frms.fraudengine.client;

import com.se.frms.fraudengine.dto.DecisionRequest;
import com.se.frms.fraudengine.dto.DecisionResponse;
import com.se.frms.fraudengine.exception.ExternalServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@RequiredArgsConstructor
@Slf4j
public class DecisionClient {

    @Qualifier("restClientBuilder")
    private final RestClient.Builder restClientBuilder;
    @Qualifier("directRestClientBuilder")
    private final RestClient.Builder directRestClientBuilder;

    @Value("${frms.decision.base-url}")
    private String decisionBaseUrl;

    @Value("${frms.decision.path}")
    private String decisionPath;

    public DecisionResponse decide(DecisionRequest request) {
        log.info("Calling Decision Service for transactionId={}, totalRiskScore={}", request.transactionId(), request.totalRiskScore());
        try {
            DecisionResponse response = restClientBuilder().build()
                    .post()
                    .uri(decisionBaseUrl + decisionPath)
                    .body(request)
                    .retrieve()
                    .body(DecisionResponse.class);
            log.info(
                    "Decision Service response transactionId={}, finalDecision={}",
                    request.transactionId(),
                    response != null ? response.finalDecision() : null
            );
            return response;
        } catch (RestClientException ex) {
            log.error("Decision Service call failed for transactionId={}", request.transactionId(), ex);
            throw new ExternalServiceException("Unable to decide fraud outcome", ex);
        }
    }

    private RestClient.Builder restClientBuilder() {
        return isLocalUrl(decisionBaseUrl) ? directRestClientBuilder : restClientBuilder;
    }

    private boolean isLocalUrl(String url) {
        return url != null && (url.contains("localhost") || url.contains("127.0.0.1"));
    }
}
