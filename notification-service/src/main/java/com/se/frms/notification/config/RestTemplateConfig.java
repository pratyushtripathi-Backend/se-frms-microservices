package com.se.frms.notification.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    /**
     * Used for the MSG24x7 SMS calls and the monolith recipient refresh. Without
     * timeouts a provider that stops answering would hold a delivery thread forever;
     * with them, a hung call becomes a normal FAILED send that the retry flow handles.
     */
    @Bean
    public RestTemplate restTemplate(
            @Value("${notification.http.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${notification.http.read-timeout-ms:5000}") int readTimeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(requestFactory);
    }
}
