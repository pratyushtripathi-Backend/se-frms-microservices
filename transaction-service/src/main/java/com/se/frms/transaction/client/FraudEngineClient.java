package com.se.frms.transaction.client;

import com.se.frms.transaction.dto.FraudEvaluationRequest;
import com.se.frms.transaction.dto.FraudEvaluationResponse;
import com.se.frms.transaction.exception.FraudEngineException;
import java.net.ConnectException;
import java.net.UnknownHostException;
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

    /**
     * Waits before the 2nd, 3rd and 4th attempt. Only used when the request NEVER
     * reached fraud-engine (see neverReachedFraudEngine), so a retry can never evaluate
     * the same transaction twice.
     */
    private static final long[] RETRY_DELAYS_MS = {1000, 2000, 4000};

    private final RestClient.Builder restClientBuilder;
    // Built once and reused (thread-safe) instead of building a new client per call.
    private volatile RestClient restClient;

    @Value("${frms.fraud-engine.base-url}")
    private String fraudEngineBaseUrl;

    @Value("${frms.fraud-engine.evaluation-path}")
    private String evaluationPath;

    public FraudEvaluationResponse evaluate(FraudEvaluationRequest request) {
        log.debug("Calling Fraud Engine for transactionId={}", request.transactionId());
        for (int attempt = 0; ; attempt++) {
            try {
                FraudEvaluationResponse response = call(request);
                log.debug(
                        "Fraud Engine response received for transactionId={}, finalDecision={}, riskScore={}",
                        request.transactionId(),
                        response != null ? response.finalDecision() : null,
                        response != null ? response.totalRiskScore() : null
                );
                return response;
            } catch (RuntimeException ex) {
                if (attempt < RETRY_DELAYS_MS.length && neverReachedFraudEngine(ex)) {
                    log.warn("Fraud Engine not reachable for transactionId={}, retry {} of {} in {} ms, reason={}",
                            request.transactionId(), attempt + 1, RETRY_DELAYS_MS.length,
                            RETRY_DELAYS_MS[attempt], ex.toString());
                    if (sleep(RETRY_DELAYS_MS[attempt])) {
                        continue;
                    }
                }
                // Same outcome as before once retries are used up (or for any other error).
                if (ex instanceof RestClientException restClientException) {
                    log.error("Fraud Engine call failed for transactionId={}", request.transactionId(), ex);
                    throw new FraudEngineException("Unable to evaluate transaction with Fraud Engine", restClientException);
                }
                throw ex;
            }
        }
    }

    private FraudEvaluationResponse call(FraudEvaluationRequest request) {
        return restClient()
                .post()
                .uri(fraudEngineBaseUrl + evaluationPath)
                .body(request)
                .retrieve()
                .body(FraudEvaluationResponse.class);
    }

    /**
     * True only for failures that happen BEFORE the request is sent: no free pooled
     * connection, connection refused / connect timeout, unknown host, or no
     * fraud-engine instance registered in Eureka. A read timeout or any HTTP error
     * response is NOT retried (fraud-engine may already have evaluated it).
     */
    static boolean neverReachedFraudEngine(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConnectException
                    || t instanceof UnknownHostException
                    || t instanceof org.apache.hc.client5.http.ConnectTimeoutException
                    || t instanceof org.apache.hc.core5.http.ConnectionRequestTimeoutException) {
                return true;
            }
            if (t instanceof IllegalStateException
                    && t.getMessage() != null
                    && t.getMessage().startsWith("No instances available")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private RestClient restClient() {
        RestClient client = restClient;
        if (client == null) {
            client = restClientBuilder.build();
            restClient = client;
        }
        return client;
    }
}
