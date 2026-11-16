package com.se.frms.ai.config;

import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/**
 * One shared, pooled HTTP client for ai-service's single outbound target (the
 * Claude API) - same pattern as decision-service's HttpClientConfig, sized
 * down since there's only one downstream host instead of several internal
 * services.
 */
@Configuration
public class HttpClientConfig {

    @Bean(destroyMethod = "destroy")
    public HttpComponentsClientHttpRequestFactory internalClientHttpRequestFactory(
            @Value("${frms.http.max-connections:50}") int maxConnections,
            @Value("${frms.http.max-connections-per-route:50}") int maxConnectionsPerRoute,
            @Value("${frms.http.connect-timeout-ms:3000}") long connectTimeoutMs,
            @Value("${frms.http.read-timeout-ms:20000}") long readTimeoutMs,
            @Value("${frms.http.pool-wait-timeout-ms:3000}") long poolWaitTimeoutMs
    ) {
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(maxConnections)
                .setMaxConnPerRoute(maxConnectionsPerRoute)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                        .setSocketTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                        .setValidateAfterInactivity(TimeValue.ofSeconds(2))
                        .build())
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(poolWaitTimeoutMs))
                        .setResponseTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                        .build())
                .evictIdleConnections(TimeValue.of(15, TimeUnit.SECONDS))
                .evictExpiredConnections()
                .build();

        return new HttpComponentsClientHttpRequestFactory(httpClient);
    }
}
