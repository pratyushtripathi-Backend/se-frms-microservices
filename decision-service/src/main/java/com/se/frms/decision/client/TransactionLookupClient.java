package com.se.frms.decision.client;

import com.se.frms.decision.dto.TransactionLookupResponse;
import com.se.frms.decision.exception.ExternalServiceException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Fetches amount + mode(channel) from transaction-service for the case-management view. */
@Component
@Slf4j
public class TransactionLookupClient {

    private final RestClient.Builder directRestClientBuilder;
    private final RestClient.Builder loadBalancedRestClientBuilder;
    // Built once and reused (thread-safe) instead of building a new client per call.
    private volatile RestClient restClient;

    // Explicit constructor - see ScoringLookupClient for why this isn't
    // @RequiredArgsConstructor.
    public TransactionLookupClient(
            @Qualifier("directLookupRestClientBuilder") RestClient.Builder directRestClientBuilder,
            @Qualifier("loadBalancedLookupRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder
    ) {
        this.directRestClientBuilder = directRestClientBuilder;
        this.loadBalancedRestClientBuilder = loadBalancedRestClientBuilder;
    }

    @Value("${frms.transaction.base-url:http://localhost:8099}")
    private String transactionBaseUrl;

    @Value("${frms.transaction.path:/api/v1/transactions}")
    private String transactionPath;

    public TransactionLookupResponse getByTransactionId(UUID transactionId) {
        try {
            return restClient()
                    .get()
                    .uri(transactionBaseUrl + transactionPath + "/{transactionId}", transactionId)
                    .retrieve()
                    .body(TransactionLookupResponse.class);
        } catch (RestClientException ex) {
            log.warn("Transaction Service lookup failed for transactionId={}", transactionId, ex);
            throw new ExternalServiceException("Unable to fetch transaction details", ex);
        }
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
        return isLocalUrl(transactionBaseUrl) ? directRestClientBuilder : loadBalancedRestClientBuilder;
    }

    private boolean isLocalUrl(String url) {
        return url != null && (url.contains("localhost") || url.contains("127.0.0.1"));
    }
}
