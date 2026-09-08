package com.se.frms.decision.config;

import com.se.frms.decision.filter.CorrelationIdFilter;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

/**
 * Forwards the current thread's correlation ID (set by CorrelationIdFilter)
 * as X-Request-Id on outbound calls to scoring-service/transaction-service
 * made while building the case-management view, so those services log
 * against the same ID as the inbound /api/v1/decisions/cases request.
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
