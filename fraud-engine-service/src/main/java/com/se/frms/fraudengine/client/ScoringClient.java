package com.se.frms.fraudengine.client;

import com.se.frms.fraudengine.dto.ScoringRequest;
import com.se.frms.fraudengine.dto.ScoringResponse;
import com.se.frms.fraudengine.exception.ExternalServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@Slf4j
public class ScoringClient {

    private final RestClient.Builder restClientBuilder;
    private final RestClient.Builder directRestClientBuilder;
    // Built once and reused (thread-safe) instead of building a new client per call.
    private volatile RestClient restClient;

    static final String RULES_VERSION_HEADER = "X-Frms-Rules-Version";

    // Rules version Scoring Service has confirmed it stores (null = send full rules).
    private volatile String confirmedRulesVersion;

    // Set false to always send the full rule list (previous behaviour).
    @Value("${frms.scoring.compact-rules.enabled:true}")
    private boolean compactRequestsEnabled = true;

    @Value("${frms.scoring.base-url}")
    private String scoringBaseUrl;

    @Value("${frms.scoring.path}")
    private String scoringPath;

    public ScoringClient(@Qualifier("restClientBuilder") RestClient.Builder restClientBuilder,
                         @Qualifier("directRestClientBuilder") RestClient.Builder directRestClientBuilder) {
        this.restClientBuilder = restClientBuilder;
        this.directRestClientBuilder = directRestClientBuilder;
    }

    /**
     * Scores one transaction.
     *
     * The active rule list is the same for every transaction until the rules change,
     * so it is not sent every time: once Scoring Service has confirmed (response header
     * X-Frms-Rules-Version) that it stored the rules of a version, later requests send
     * only the transaction's matched blacklist rules plus that version, and Scoring
     * Service puts its stored rules in front - the same list, in the same order, as the
     * full request. Same rules, same score; only less JSON on both sides.
     *
     * If Scoring Service does not have that version (restart, another instance, rules
     * changed) it answers 409 and the full request is sent at once, as before. A Scoring
     * Service without this feature never sends the header, so it always gets full requests.
     */
    public ScoringResponse score(ScoringRequest request) {
        log.debug("Calling Scoring Service for transactionId={}", request.transactionId());
        String version = request.rulesVersion();
        Integer baseCount = request.rulesBaseCount();
        boolean compact = compactRequestsEnabled
                && version != null
                && baseCount != null && baseCount > 0
                && request.activeRules() != null && request.activeRules().size() >= baseCount
                && version.equals(confirmedRulesVersion);
        try {
            if (compact) {
                ScoringRequest compactRequest = new ScoringRequest(
                        request.transactionId(),
                        request.activeRules().subList(baseCount, request.activeRules().size()),
                        request.transactionData(),
                        version,
                        null,
                        true
                );
                try {
                    return send(compactRequest, version);
                } catch (HttpClientErrorException.Conflict unknownVersion) {
                    // Scoring Service does not have these rules (yet): send them in full.
                    confirmedRulesVersion = null;
                    log.debug("Scoring Service does not have rules version {}, sending full rules", version);
                }
            }
            return send(request, version);
        } catch (RestClientException ex) {
            log.error("Scoring Service call failed for transactionId={}", request.transactionId(), ex);
            throw new ExternalServiceException("Unable to calculate fraud score", ex);
        }
    }

    private ScoringResponse send(ScoringRequest request, String version) {
        ResponseEntity<ScoringResponse> entity = restClient()
                .post()
                .uri(scoringBaseUrl + scoringPath)
                .body(request)
                .retrieve()
                .toEntity(ScoringResponse.class);
        if (version != null && version.equals(entity.getHeaders().getFirst(RULES_VERSION_HEADER))) {
            confirmedRulesVersion = version;
        }
        ScoringResponse response = entity.getBody();
        log.debug(
                "Scoring Service response transactionId={}, totalRiskScore={}",
                request.transactionId(),
                response != null ? response.totalRiskScore() : null
        );
        return response;
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
        return isLocalUrl(scoringBaseUrl) ? directRestClientBuilder : restClientBuilder;
    }

    private boolean isLocalUrl(String url) {
        return url != null && (url.contains("localhost") || url.contains("127.0.0.1"));
    }
}