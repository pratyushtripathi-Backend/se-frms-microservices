package com.se.frms.scoring.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * This service is otherwise called-only (no outbound HTTP calls before this) - this is its first
 * and only one (AiScoringClient). No pooling complexity needed: every call already runs on the
 * dedicated, bounded aiScoringExecutor (AiScoringAsyncConfig), never on a request thread, so one
 * blocking factory with a long read timeout is enough.
 */
@Configuration
public class AiRestClientConfig {

    @Bean
    public RestClient.Builder aiRestClientBuilder(
            @Value("${frms.ai.http.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${frms.ai.http.read-timeout-ms:25000}") int readTimeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        // An LLM completion takes materially longer than this service's own internal rule
        // evaluation - irrelevant to the live response either way, since this call never runs on
        // the request thread.
        requestFactory.setReadTimeout(readTimeoutMs);
        return RestClient.builder().requestFactory(requestFactory);
    }
}
