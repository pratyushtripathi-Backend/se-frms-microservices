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

    /**
     * Separate RestTemplate, dedicated to ai-service calls, with a much
     * longer read timeout than the one above - an LLM completion takes
     * materially longer than the MSG24x7/monolith calls that bean is sized
     * for, and reusing its 5s timeout would fail every AI draft request.
     * Field name "aiRestTemplate" on AiDraftTemplateClient resolves to this
     * bean by name, the same way "restTemplate" above already resolves
     * unambiguously for MonolithAdminClient and Msg24x7SmsSenderService.
     */
    @Bean
    public RestTemplate aiRestTemplate(
            @Value("${frms.ai.http.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${frms.ai.http.read-timeout-ms:25000}") int readTimeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(requestFactory);
    }
}
