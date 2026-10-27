package com.se.frms.fraudengine.client;

import com.se.frms.fraudengine.dto.DecisionBatchItem;
import com.se.frms.fraudengine.dto.DecisionRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.HttpClientErrorException;
import com.se.frms.fraudengine.dto.DecisionResponse;
import com.se.frms.fraudengine.exception.ExternalServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@Slf4j
public class DecisionClient {

    private final RestClient.Builder restClientBuilder;
    private final RestClient.Builder directRestClientBuilder;
    // Built once and reused (thread-safe) instead of building a new client per call.
    private volatile RestClient restClient;

    @Value("${frms.decision.base-url}")
    private String decisionBaseUrl;

    @Value("${frms.decision.path}")
    private String decisionPath;

    // Batch endpoint (decideBatch); false = always one call per transaction.
    @Value("${frms.decision.batch.enabled:true}")
    private boolean batchEnabled = true;

    // Until when the batch endpoint is known to be missing (older Decision Service).
    private volatile long batchUnsupportedUntil;

    public DecisionClient(@Qualifier("restClientBuilder") RestClient.Builder restClientBuilder,
                          @Qualifier("directRestClientBuilder") RestClient.Builder directRestClientBuilder) {
        this.restClientBuilder = restClientBuilder;
        this.directRestClientBuilder = directRestClientBuilder;
    }

    public DecisionResponse decide(DecisionRequest request) {
        log.debug("Calling Decision Service for transactionId={}, totalRiskScore={}", request.transactionId(), request.totalRiskScore());
        try {
            DecisionResponse response = restClient()
                    .post()
                    .uri(decisionBaseUrl + decisionPath)
                    .body(request)
                    .retrieve()
                    .body(DecisionResponse.class);
            log.debug(
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

    /**
     * Decides several transactions with ONE call to POST /api/v1/decisions/batch instead
     * of one call each. Each transaction is decided by the same Decision Service code and
     * gets the same response - or the same error (ExternalServiceException) - as decide()
     * would give it alone, in the same order as the requests.
     *
     * If Decision Service does not have the batch endpoint (older version: 404/405), each
     * transaction is sent with decide() instead, for the next minute. If the batch call
     * itself fails, every transaction in it fails exactly as a failed single call would.
     */
    public List<BatchOutcome<DecisionResponse>> decideBatch(List<DecisionRequest> requests, Executor fallbackExecutor) {
        if (requests.isEmpty()) {
            return List.of();
        }
        if (!batchEnabled || System.currentTimeMillis() < batchUnsupportedUntil) {
            return decideEach(requests, fallbackExecutor);
        }
        try {
            List<DecisionBatchItem> items = restClient()
                    .post()
                    .uri(decisionBaseUrl + decisionPath + "/batch")
                    .body(requests)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<DecisionBatchItem>>() { });
            if (items == null || items.size() != requests.size()) {
                throw new RestClientException("Decision Service batch answered " + (items == null ? 0 : items.size())
                        + " results for " + requests.size() + " transactions");
            }
            List<BatchOutcome<DecisionResponse>> outcomes = new ArrayList<>(requests.size());
            for (int i = 0; i < items.size(); i++) {
                DecisionBatchItem item = items.get(i);
                if (item.status() == 200 && item.response() != null) {
                    outcomes.add(BatchOutcome.ok(item.response()));
                } else {
                    RestClientException cause = new RestClientException(
                            "Decision Service answered " + item.status() + ": " + item.error());
                    log.error("Decision Service call failed for transactionId={}", requests.get(i).transactionId(), cause);
                    outcomes.add(BatchOutcome.failed(new ExternalServiceException("Unable to decide fraud outcome", cause)));
                }
            }
            return outcomes;
        } catch (RestClientResponseException ex) {
            if (ScoringClient.batchEndpointMissing(ex, "Unable to process decision request")) {
                batchUnsupportedUntil = System.currentTimeMillis() + 60_000;
                log.warn("Decision Service has no batch endpoint (HTTP {}), deciding one by one for the next minute",
                        ex.getStatusCode().value());
                return decideEach(requests, fallbackExecutor);
            }
            return failAll(requests, ex);
        } catch (RestClientException ex) {
            return failAll(requests, ex);
        }
    }

    private List<BatchOutcome<DecisionResponse>> decideEach(List<DecisionRequest> requests, Executor executor) {
        List<CompletableFuture<BatchOutcome<DecisionResponse>>> futures = requests.stream()
                .map(r -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return BatchOutcome.ok(decide(r));
                    } catch (RuntimeException ex) {
                        return BatchOutcome.<DecisionResponse>failed(ex);
                    }
                }, executor))
                .toList();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private List<BatchOutcome<DecisionResponse>> failAll(List<DecisionRequest> requests, RestClientException ex) {
        log.error("Decision Service batch call failed size={}", requests.size(), ex);
        ExternalServiceException error = new ExternalServiceException("Unable to decide fraud outcome", ex);
        List<BatchOutcome<DecisionResponse>> outcomes = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            outcomes.add(BatchOutcome.failed(error));
        }
        return outcomes;
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
        return isLocalUrl(decisionBaseUrl) ? directRestClientBuilder : restClientBuilder;
    }

    private boolean isLocalUrl(String url) {
        return url != null && (url.contains("localhost") || url.contains("127.0.0.1"));
    }
}