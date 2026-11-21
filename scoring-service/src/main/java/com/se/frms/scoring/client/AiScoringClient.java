package com.se.frms.scoring.client;

import com.se.frms.scoring.dto.AiRiskScoreRequest;
import com.se.frms.scoring.dto.AiRiskScoreResponse;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls ai-service's prompt-based /ai/risk-score for shadow-scoring evaluation only (see
 * AiScoringAsyncService). Always invoked off the request thread - see AiScoringAsyncConfig.
 */
@Component
public class AiScoringClient {

    private final RestClient restClient;
    private final String aiBaseUrl;
    private final String aiPath;

    public AiScoringClient(
            @Qualifier("aiRestClientBuilder") RestClient.Builder aiRestClientBuilder,
            @Value("${frms.ai.base-url:http://localhost:8101}") String aiBaseUrl,
            @Value("${frms.ai.path:/api/v1/ai}") String aiPath
    ) {
        this.restClient = aiRestClientBuilder.build();
        this.aiBaseUrl = aiBaseUrl;
        this.aiPath = aiPath;
    }

    public AiRiskScoreResponse scoreRisk(Map<String, Object> features) {
        try {
            return restClient.post()
                    .uri(aiBaseUrl + aiPath + "/risk-score")
                    .body(new AiRiskScoreRequest(features))
                    .retrieve()
                    .body(AiRiskScoreResponse.class);
        } catch (RestClientException ex) {
            throw new AiScoringUnavailableException("AI risk-score call failed", ex);
        }
    }
}
