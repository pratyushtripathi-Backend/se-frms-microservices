package com.se.frms.fraudengine.config;

import lombok.RequiredArgsConstructor;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

    private final CorrelationIdRequestInterceptor correlationIdRequestInterceptor;

    @Bean
    @LoadBalanced
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder().requestInterceptor(correlationIdRequestInterceptor);
    }

    @Bean
    public RestClient.Builder directRestClientBuilder() {
        return RestClient.builder().requestInterceptor(correlationIdRequestInterceptor);
    }
}
