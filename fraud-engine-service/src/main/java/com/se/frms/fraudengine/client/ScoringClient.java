package com.se.frms.fraudengine.client;

import com.se.frms.fraudengine.dto.ScoringRequest;
import com.se.frms.fraudengine.dto.ScoringResponse;
import com.se.frms.fraudengine.exception.ExternalServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import com.se.frms.fraudengine.dto.ScoringBatchItem;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

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

    // Batch endpoint (scoreBatch); false = always one call per transaction.
    @Value("${frms.scoring.batch.enabled:true}")
    private boolean batchEnabled = true;

    // Until when the batch endpoint is known to be missing (older Scoring Service).
    private volatile long batchUnsupportedUntil;

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

    /**
     * Scores several transactions with ONE call to POST /api/v1/scoring/batch instead of
     * one call each. Each transaction is scored by the same Scoring Service code and gets
     * the same response - or the same error (ExternalServiceException) - as score() would
     * give it alone; the outcomes come back in the same order as the requests.
     *
     * Compact rules work as in score(). If Scoring Service does not have the batch
     * endpoint (older version: 404/405), each transaction is sent with score() instead,
     * for the next minute. If the batch call itself fails, every transaction in it fails
     * exactly as a failed single call would (no retry - it may already have been scored).
     */
    public List<BatchOutcome<ScoringResponse>> scoreBatch(List<ScoringRequest> requests, Executor fallbackExecutor) {
        if (requests.isEmpty()) {
            return List.of();
        }
        if (!batchEnabled || System.currentTimeMillis() < batchUnsupportedUntil) {
            return scoreEach(requests, fallbackExecutor);
        }
        try {
            return scoreBatchCall(requests, true);
        } catch (RestClientResponseException ex) {
            if (batchEndpointMissing(ex, "Unable to process scoring request")) {
                batchUnsupportedUntil = System.currentTimeMillis() + 60_000;
                log.warn("Scoring Service has no batch endpoint (HTTP {}), scoring one by one for the next minute",
                        ex.getStatusCode().value());
                return scoreEach(requests, fallbackExecutor);
            }
            return failAll(requests, ex);
        } catch (RestClientException ex) {
            return failAll(requests, ex);
        }
    }

    private List<BatchOutcome<ScoringResponse>> scoreBatchCall(List<ScoringRequest> requests, boolean allowCompact) {
        String version = requests.get(0).rulesVersion();
        boolean compact = allowCompact && compactRequestsEnabled && version != null
                && version.equals(confirmedRulesVersion)
                && requests.stream().allMatch(r -> version.equals(r.rulesVersion())
                        && r.rulesBaseCount() != null && r.rulesBaseCount() > 0
                        && r.activeRules() != null && r.activeRules().size() >= r.rulesBaseCount());
        List<ScoringRequest> toSend = compact
                ? requests.stream().map(r -> new ScoringRequest(r.transactionId(),
                        r.activeRules().subList(r.rulesBaseCount(), r.activeRules().size()),
                        r.transactionData(), version, null, true)).toList()
                : requests;

        ResponseEntity<List<ScoringBatchItem>> entity = restClient()
                .post()
                .uri(scoringBaseUrl + scoringPath + "/batch")
                .body(toSend)
                .retrieve()
                .toEntity(new ParameterizedTypeReference<List<ScoringBatchItem>>() { });
        if (version != null && version.equals(entity.getHeaders().getFirst(RULES_VERSION_HEADER))) {
            confirmedRulesVersion = version;
        }
        List<ScoringBatchItem> items = entity.getBody();
        if (items == null || items.size() != requests.size()) {
            throw new RestClientException("Scoring Service batch answered " + (items == null ? 0 : items.size())
                    + " results for " + requests.size() + " transactions");
        }

        List<BatchOutcome<ScoringResponse>> outcomes = new ArrayList<>(requests.size());
        List<Integer> resendFull = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ScoringBatchItem item = items.get(i);
            if (item.status() == 200 && item.response() != null) {
                outcomes.add(BatchOutcome.ok(item.response()));
            } else if (item.status() == 409 && compact) {
                outcomes.add(null);
                resendFull.add(i);
            } else {
                RestClientException cause = new RestClientException(
                        "Scoring Service answered " + item.status() + ": " + item.error());
                log.error("Scoring Service call failed for transactionId={}", requests.get(i).transactionId(), cause);
                outcomes.add(BatchOutcome.failed(new ExternalServiceException("Unable to calculate fraud score", cause)));
            }
        }
        if (!resendFull.isEmpty()) {
            // Scoring Service does not have these rules (yet): send them in full, as score() does.
            confirmedRulesVersion = null;
            List<ScoringRequest> full = resendFull.stream().map(requests::get).toList();
            List<BatchOutcome<ScoringResponse>> again;
            try {
                again = scoreBatchCall(full, false);
            } catch (RestClientException ex) {
                // Only these transactions fail; the others were already scored.
                again = failAll(full, ex);
            }
            for (int k = 0; k < resendFull.size(); k++) {
                outcomes.set(resendFull.get(k), again.get(k));
            }
        }
        return outcomes;
    }

    /**
     * True when the service has no batch endpoint (older version): 404 / 405, or the
     * older version's catch-all 500 answer (its handler also catches "method not
     * allowed"). In both cases nothing was processed, so one-by-one calls are safe.
     */
    static boolean batchEndpointMissing(RestClientResponseException ex, String genericErrorText) {
        int status = ex.getStatusCode().value();
        return status == 404 || status == 405
                || (status == 500 && genericErrorText.equals(ex.getResponseBodyAsString().trim()));
    }

    private List<BatchOutcome<ScoringResponse>> scoreEach(List<ScoringRequest> requests, Executor executor) {
        List<CompletableFuture<BatchOutcome<ScoringResponse>>> futures = requests.stream()
                .map(r -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return BatchOutcome.ok(score(r));
                    } catch (RuntimeException ex) {
                        return BatchOutcome.<ScoringResponse>failed(ex);
                    }
                }, executor))
                .toList();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private List<BatchOutcome<ScoringResponse>> failAll(List<ScoringRequest> requests, RestClientException ex) {
        log.error("Scoring Service batch call failed size={}", requests.size(), ex);
        ExternalServiceException error = new ExternalServiceException("Unable to calculate fraud score", ex);
        List<BatchOutcome<ScoringResponse>> outcomes = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            outcomes.add(BatchOutcome.failed(error));
        }
        return outcomes;
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