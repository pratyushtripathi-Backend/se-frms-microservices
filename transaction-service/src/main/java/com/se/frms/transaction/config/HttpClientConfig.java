package com.se.frms.transaction.config;

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
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/**
 * One shared, pooled HTTP client for all service-to-service calls of this service.
 *
 * Before: every call built a new RestClient on a builder without a request factory,
 * so each call created a fresh HTTP client and a new TCP connection, with no
 * timeouts. Under concurrent load that cost grew with every request.
 * Now: connections are kept alive and reused (pool sized for concurrency), and
 * connect / response / pool-wait timeouts stop a slow or hung service from holding
 * threads forever (a timeout surfaces as the client's normal error path).
 */
@Configuration
public class HttpClientConfig {

    @Bean(destroyMethod = "destroy")
    public HttpComponentsClientHttpRequestFactory internalClientHttpRequestFactory(
            @Value("${frms.http.max-connections:200}") int maxConnections,
            @Value("${frms.http.max-connections-per-route:100}") int maxConnectionsPerRoute,
            @Value("${frms.http.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${frms.http.read-timeout-ms:10000}") long readTimeoutMs,
            @Value("${frms.http.pool-wait-timeout-ms:2000}") long poolWaitTimeoutMs
    ) {
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(maxConnections)
                .setMaxConnPerRoute(maxConnectionsPerRoute)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                        .setSocketTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                        // Re-check a connection that sat idle, so a keep-alive connection the
                        // other service already closed is not reused (avoids sporadic resets).
                        .setValidateAfterInactivity(TimeValue.ofSeconds(2))
                        .build())
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(poolWaitTimeoutMs))
                        .setResponseTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                        .build())
                // Drop connections idle longer than Tomcat's keep-alive (20s by default).
                .evictIdleConnections(TimeValue.of(15, TimeUnit.SECONDS))
                .evictExpiredConnections()
                .build();

        return new HttpComponentsClientHttpRequestFactory(httpClient);
    }
}
