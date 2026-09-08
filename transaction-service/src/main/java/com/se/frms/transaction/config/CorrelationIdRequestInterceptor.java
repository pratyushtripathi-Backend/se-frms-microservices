package com.se.frms.transaction.config;

import com.se.frms.transaction.filter.CorrelationIdFilter;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

/**
 * Forwards the current thread's correlation ID (set by CorrelationIdFilter,
 * or explicitly by an @Async caller) as X-Request-Id on every outbound
 * RestClient call, so downstream services can log against the same ID.
 */
@Component
public class CorrelationIdRequestInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request,
            byte[] body,
            ClientHttpRequestExecution execution
    ) throws IOException {
        String requestId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (requestId != null && !requestId.isBlank()) {
            request.getHeaders().set(CorrelationIdFilter.REQUEST_ID_HEADER, requestId);
        }
        return execution.execute(request, body);
    }
}
