package com.se.frms.gateway.filter;

import com.se.frms.gateway.config.GatewaySecurityProperties;
import com.se.frms.gateway.config.RateLimiterConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Validates the JWT on every request that isn't explicitly public
 * ({@code frms.security.public-paths}), so downstream services (like
 * transaction-service) never need to see or trust a raw client request
 * directly — only requests the gateway has already authenticated reach
 * them.
 *
 * On success, the caller's identity/roles are extracted from the token and
 * forwarded as {@code X-User-Id} / {@code X-User-Roles} headers, so
 * downstream services can authorize without re-parsing the token
 * themselves.
 *
 * Disabled by default ({@code frms.security.jwt.enabled=false}) — the bank
 * integration this gateway fronts is engine-to-engine, not user login, and
 * access is meant to be controlled by caller IP instead once that's
 * configured. This filter (and auth-service's /login) is left in place,
 * not deleted, so token-based auth can be switched back on later by
 * flipping the property to true, without rebuilding any of this.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "frms.security.jwt.enabled", havingValue = "true", matchIfMissing = false)
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    public static final String USER_ROLES_HEADER = "X-User-Roles";

    private final GatewaySecurityProperties securityProperties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        String authorizationHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return unauthorized(exchange, "Missing or malformed Authorization header");
        }

        String token = authorizationHeader.substring("Bearer ".length());
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(signingKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            String userId = claims.getSubject();
            String roles = claims.get("roles", String.class);

            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .header(RateLimiterConfig.USER_ID_HEADER, userId)
                    .header(USER_ROLES_HEADER, roles != null ? roles : "")
                    .build();

            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        } catch (ExpiredJwtException ex) {
            return unauthorized(exchange, "JWT token has expired");
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("JWT validation failed path={}, reason={}", path, ex.getMessage());
            return unauthorized(exchange, "Invalid JWT token");
        }
    }

    private boolean isPublicPath(String path) {
        return securityProperties.getPublicPaths().stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private Key signingKey() {
        byte[] keyBytes = securityProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = String.format(
                "{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"%s\",\"path\":\"%s\"}",
                message,
                exchange.getRequest().getURI().getPath()
        );
        return response.writeWith(Mono.just(
                response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))
        ));
    }

    @Override
    public int getOrder() {
        // After correlation ID assignment, before routing/rate-limiting.
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
