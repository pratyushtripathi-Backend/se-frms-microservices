package com.se.frms.transaction.client;

import com.se.frms.transaction.dto.FraudEvaluationRequest;
import com.se.frms.transaction.dto.FraudEvaluationResponse;
import com.se.frms.transaction.exception.FraudEngineException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@RequiredArgsConstructor
@Slf4j
public class FraudEngineClient {

    private final RestClient.Builder restClientBuilder;

    @Value("${frms.fraud-engine.base-url}")
    private String fraudEngineBaseUrl;

    @Value("${frms.fraud-engine.evaluation-path}")
    private String evaluationPath;

    public FraudEvaluationResponse evaluate(FraudEvaluationRequest request) {
        log.info("Calling Fraud Engine for transactionId={}", request.transactionId());
        try {
            FraudEvaluationResponse response = restClientBuilder.build()
                    .post()
                    .uri(fraudEngineBaseUrl + evaluationPath)
                    .body(request)
                    .retrieve()
                    .body(FraudEvaluationResponse.class);
            log.info(
                    "Fraud Engine response received for transactionId={}, finalDecision={}, riskScore={}",
                    request.transactionId(),
                    response != null ? response.finalDecision() : null,
                    response != null ? response.totalRiskScore() : null
            );
            return response;
        } catch (RestClientException ex) {
            log.error("Fraud Engine call failed for transactionId={}", request.transactionId(), ex);
            throw new FraudEngineException("Unable to evaluate transaction with Fraud Engine", ex);
        }
    }
}

