package com.se.frms.transaction.config;

import lombok.RequiredArgsConstructor;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

    private final CorrelationIdRequestInterceptor correlationIdRequestInterceptor;
    // Shared pooled HTTP client with timeouts - see HttpClientConfig.
    private final ClientHttpRequestFactory internalClientHttpRequestFactory;

    @Bean
    @LoadBalanced
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder()
                .requestFactory(internalClientHttpRequestFactory)
                .requestInterceptor(correlationIdRequestInterceptor);
    }
}
