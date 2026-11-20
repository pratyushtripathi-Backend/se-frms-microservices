package com.se.frms.analytics.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * This service is otherwise Kafka-consumer-only - this is its first and
 * only outbound HTTP call (AiTrendSummaryClient), so a single builder with
 * plain timeouts is enough. No pooling complexity needed for one
 * low-volume, on-demand call.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder(
            @Value("${frms.ai.http.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${frms.ai.http.read-timeout-ms:25000}") int readTimeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        // An LLM completion takes materially longer than this service's
        // own (currently nonexistent) internal lookups would.
        requestFactory.setReadTimeout(readTimeoutMs);
        return RestClient.builder().requestFactory(requestFactory);
    }
}
