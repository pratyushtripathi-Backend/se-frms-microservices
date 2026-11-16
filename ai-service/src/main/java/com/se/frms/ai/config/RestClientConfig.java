package com.se.frms.ai.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * ai-service has exactly one outbound target (the external Claude API), not
 * another Eureka-registered service, so there's no need for the
 * direct-vs-@LoadBalanced builder split decision-service uses for its
 * internal lookups - one plain builder on the shared pooled request factory
 * is enough.
 */
@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

    private final ClientHttpRequestFactory internalClientHttpRequestFactory;

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder().requestFactory(internalClientHttpRequestFactory);
    }
}
