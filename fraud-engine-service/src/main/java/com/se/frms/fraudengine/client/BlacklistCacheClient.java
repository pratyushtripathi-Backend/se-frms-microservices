package com.se.frms.fraudengine.client;

import com.se.frms.fraudengine.dto.ActiveBlacklistResponse;
import com.se.frms.fraudengine.exception.ExternalServiceException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@RequiredArgsConstructor
@Slf4j
public class BlacklistCacheClient {

    @Qualifier("restClientBuilder")
    private final RestClient.Builder restClientBuilder;
    @Qualifier("directRestClientBuilder")
    private final RestClient.Builder directRestClientBuilder;

    @Value("${frms.rule-cache.base-url}")
    private String ruleCacheBaseUrl;

    @Value("${frms.rule-cache.active-blacklist-path}")
    private String activeBlacklistPath;

    public List<ActiveBlacklistResponse> getActiveBlacklist() {
        log.info("Fetching active blacklist from Rule Cache");
        try {
            List<ActiveBlacklistResponse> blacklist = restClientBuilder().build()
                    .get()
                    .uri(ruleCacheBaseUrl + activeBlacklistPath)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            log.info("Fetched {} active blacklist entries from Rule Cache", blacklist != null ? blacklist.size() : 0);
            return blacklist != null ? blacklist : List.of();
        } catch (RestClientException ex) {
            log.error("Rule Cache blacklist call failed", ex);
            throw new ExternalServiceException("Unable to fetch active blacklist from Rule Cache", ex);
        }
    }

    private RestClient.Builder restClientBuilder() {
        return isLocalUrl(ruleCacheBaseUrl) ? directRestClientBuilder : restClientBuilder;
    }

    private boolean isLocalUrl(String url) {
        return url != null && (url.contains("localhost") || url.contains("127.0.0.1"));
    }
}
