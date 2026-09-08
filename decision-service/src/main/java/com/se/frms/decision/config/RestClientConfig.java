package com.se.frms.decision.config;

import lombok.RequiredArgsConstructor;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

    private final CorrelationIdRequestInterceptor correlationIdRequestInterceptor;

    // Existing bean, left exactly as-is (no interceptor, no @LoadBalanced) so
    // RuleCacheDecisionPolicyClient's literal http://localhost:8093 call
    // keeps working unchanged - that client isn't tied to a request thread
    // (it runs on a schedule), so correlation-ID propagation doesn't apply.
    // @Primary because RuleCacheDecisionPolicyClient injects this
    // unqualified, by field name, and this build doesn't retain constructor
    // parameter names - without @Primary, adding the two beans below turns
    // that injection point ambiguous and breaks it.
    @Bean
    @Primary
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    // New, for the case-management lookups (scoring-service/transaction-service):
    // direct builder for literal localhost URLs (local dev, same pattern as
    // fraud-engine-service's directRestClientBuilder).
    @Bean
    public RestClient.Builder directLookupRestClientBuilder() {
        return RestClient.builder().requestInterceptor(correlationIdRequestInterceptor);
    }

    // Load-balanced builder for service-id URLs (e.g. http://scoring-service
    // resolved via Eureka), used when the configured base-url isn't localhost.
    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedLookupRestClientBuilder() {
        return RestClient.builder().requestInterceptor(correlationIdRequestInterceptor);
    }
}
