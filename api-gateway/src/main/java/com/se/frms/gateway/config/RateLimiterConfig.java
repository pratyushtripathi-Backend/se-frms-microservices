package com.se.frms.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Determines the bucket each request is rate-limited against.
 *
 * Prefers an authenticated caller identity (set by {@link
 * com.se.frms.gateway.filter.JwtAuthenticationFilter} once the JWT is
 * validated) so each user/client gets its own quota; falls back to the
 * caller's remote address for unauthenticated/public routes so one IP can't
 * exhaust another's budget.
 */
@Configuration
public class RateLimiterConfig {

    public static final String USER_ID_HEADER = "X-User-Id";

    @Bean
    public KeyResolver clientKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst(USER_ID_HEADER);
            if (userId != null && !userId.isBlank()) {
                return Mono.just(userId);
            }
            String remoteAddress = exchange.getRequest().getRemoteAddress() != null
                    ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                    : "unknown";
            return Mono.just(remoteAddress);
        };
    }
}
