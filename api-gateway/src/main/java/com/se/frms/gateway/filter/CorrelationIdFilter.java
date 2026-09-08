package com.se.frms.gateway.filter;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Assigns (or propagates) a request-correlation ID for every call that
 * passes through the gateway, so a single transaction can be traced across
 * every downstream microservice's logs.
 *
 * - If the caller already sent X-Request-Id, it is kept as-is.
 * - Otherwise a new UUID is generated.
 * - The ID is added to the downstream request headers AND the response
 *   headers, and logged with request method/path/status/latency.
 */
@Slf4j
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String MDC_KEY = "requestId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest originalRequest = exchange.getRequest();
        String requestId = originalRequest.getHeaders().getFirst(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        final String finalRequestId = requestId;

        ServerHttpRequest mutatedRequest = originalRequest.mutate()
                .header(REQUEST_ID_HEADER, finalRequestId)
                .build();
        exchange.getResponse().getHeaders().add(REQUEST_ID_HEADER, finalRequestId);

        long startedAt = System.currentTimeMillis();
        MDC.put(MDC_KEY, finalRequestId);
        log.info(
                "Incoming {} {} requestId={}",
                originalRequest.getMethod(),
                originalRequest.getURI().getPath(),
                finalRequestId
        );

        return chain.filter(exchange.mutate().request(mutatedRequest).build())
                .doFinally(signal -> {
                    long elapsedMs = System.currentTimeMillis() - startedAt;
                    log.info(
                            "Completed {} {} requestId={}, status={}, elapsedMs={}",
                            originalRequest.getMethod(),
                            originalRequest.getURI().getPath(),
                            finalRequestId,
                            exchange.getResponse().getStatusCode(),
                            elapsedMs
                    );
                    MDC.remove(MDC_KEY);
                });
    }

    @Override
    public int getOrder() {
        // Run first, before auth/rate-limiting, so every request (even
        // rejected ones) is logged with a correlation ID.
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
