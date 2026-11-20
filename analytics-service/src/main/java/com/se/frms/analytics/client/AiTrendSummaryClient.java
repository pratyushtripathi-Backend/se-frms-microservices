package com.se.frms.analytics.client;

import com.se.frms.analytics.dto.AiTrendSummaryRequest;
import com.se.frms.analytics.dto.AiTrendSummaryResponse;
import com.se.frms.analytics.dto.FraudTrendResponse;
import com.se.frms.analytics.dto.RulePerformanceResponse;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Asks ai-service to turn this service's already-computed fraud-trend and
 * rule-performance numbers into a plain-English summary, for the
 * GET /api/v1/analytics/ai-summary endpoint.
 */
@Component
@Slf4j
public class AiTrendSummaryClient {

    private final RestClient restClient;

    public AiTrendSummaryClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Value("${frms.ai.base-url:http://localhost:8101}")
    private String aiBaseUrl;

    @Value("${frms.ai.path:/api/v1/ai}")
    private String aiPath;

    public String summarizeTrends(List<FraudTrendResponse> fraudTrend, List<RulePerformanceResponse> rulePerformance) {
        AiTrendSummaryRequest request = new AiTrendSummaryRequest(
                Map.of("items", fraudTrend),
                Map.of("items", rulePerformance)
        );
        try {
            AiTrendSummaryResponse response = restClient.post()
                    .uri(aiBaseUrl + aiPath + "/summarize-trends")
                    .body(request)
                    .retrieve()
                    .body(AiTrendSummaryResponse.class);
            return response == null ? null : response.summary();
        } catch (RestClientException ex) {
            log.warn("AI trend summary call failed", ex);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to summarize trends via ai-service", ex);
        }
    }
}
