package com.se.frms.fraudengine.client;

import com.se.frms.fraudengine.dto.ScoringRequest;
import com.se.frms.fraudengine.dto.ScoringResponse;
import com.se.frms.fraudengine.exception.ExternalServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@RequiredArgsConstructor
@Slf4j
public class ScoringClient {

    private final RestClient.Builder restClientBuilder;

    @Value("${frms.scoring.base-url}")
    private String scoringBaseUrl;

    @Value("${frms.scoring.path}")
    private String scoringPath;

    public ScoringResponse score(ScoringRequest request) {
        log.info("Calling Scoring Service for transactionId={}", request.transactionId());
        try {
            ScoringResponse response = restClientBuilder.build()
                    .post()
                    .uri(scoringBaseUrl + scoringPath)
                    .body(request)
                    .retrieve()
                    .body(ScoringResponse.class);
            log.info(
                    "Scoring Service response transactionId={}, totalRiskScore={}",
                    request.transactionId(),
                    response != null ? response.totalRiskScore() : null
            );
            return response;
        } catch (RestClientException ex) {
            log.error("Scoring Service call failed for transactionId={}", request.transactionId(), ex);
            throw new ExternalServiceException("Unable to calculate fraud score", ex);
        }
    }
}
