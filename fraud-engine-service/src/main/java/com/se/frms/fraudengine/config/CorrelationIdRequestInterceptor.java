package com.se.frms.fraudengine.config;

import com.se.frms.fraudengine.filter.CorrelationIdFilter;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

/**
 * Forwards the current thread's correlation ID (set by CorrelationIdFilter)
 * as X-Request-Id on every outbound RestClient call (to scoring-service,
 * decision-service, rule-cache-service), so those services' logs trace to
 * the same request.
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
