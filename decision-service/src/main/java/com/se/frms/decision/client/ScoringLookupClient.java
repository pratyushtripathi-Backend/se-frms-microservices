package com.se.frms.decision.client;

import com.se.frms.decision.dto.ScoringLookupResponse;
import com.se.frms.decision.exception.ExternalServiceException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Fetches one scoring attempt's matched-rule detail for the case-management
 * view, by the scoringId already stored on the Decision row - not the
 * "latest by transaction" scoring endpoint, since a transaction can have
 * more than one scoring attempt and we want the exact one this decision was
 * made from.
 */
@Component
@Slf4j
public class ScoringLookupClient {

    private final RestClient.Builder directRestClientBuilder;
    private final RestClient.Builder loadBalancedRestClientBuilder;

    // Explicit constructor (not @RequiredArgsConstructor) so the @Qualifier
    // on each parameter is guaranteed to apply - with three RestClient.Builder
    // beans in this service, relying on Lombok to copy field-level
    // @Qualifier annotations onto the generated constructor is fragile and
    // failed to resolve them in practice.
    public ScoringLookupClient(
            @Qualifier("directLookupRestClientBuilder") RestClient.Builder directRestClientBuilder,
            @Qualifier("loadBalancedLookupRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder
    ) {
        this.directRestClientBuilder = directRestClientBuilder;
        this.loadBalancedRestClientBuilder = loadBalancedRestClientBuilder;
    }

    @Value("${frms.scoring.base-url:http://localhost:8094}")
    private String scoringBaseUrl;

    @Value("${frms.scoring.path:/api/v1/scoring}")
    private String scoringPath;

    public ScoringLookupResponse getByScoringId(UUID scoringId) {
        try {
            return restClientBuilder().build()
                    .get()
                    .uri(scoringBaseUrl + scoringPath + "/{scoringId}", scoringId)
                    .retrieve()
                    .body(ScoringLookupResponse.class);
        } catch (RestClientException ex) {
            log.warn("Scoring Service lookup failed for scoringId={}", scoringId, ex);
            throw new ExternalServiceException("Unable to fetch scoring details", ex);
        }
    }

    private RestClient.Builder restClientBuilder() {
        return isLocalUrl(scoringBaseUrl) ? directRestClientBuilder : loadBalancedRestClientBuilder;
    }

    private boolean isLocalUrl(String url) {
        return url != null && (url.contains("localhost") || url.contains("127.0.0.1"));
    }
}
