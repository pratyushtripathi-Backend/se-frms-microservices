package com.se.frms.gateway.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.cloud.gateway.filter.ratelimit.AbstractRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import reactor.core.publisher.Mono;

/**
 * Same token-bucket rate limit as Spring's RedisRateLimiter (same replenishRate /
 * burstCapacity / requestedTokens settings, same 429 response and X-RateLimit-* headers),
 * but the buckets are kept in this gateway's memory instead of Redis. Used on the
 * POST /api/v1/transactions route, so a transaction no longer waits for a Redis
 * round-trip (Lua script) before it is forwarded.
 *
 * Difference to keep in mind: the limit applies per gateway instance. With one gateway
 * instance (today) it is identical; with N instances behind a load balancer each allows
 * the configured rate.
 *
 * Registered as bean "localRateLimiter" (autowireCandidate = false, see
 * RateLimiterConfig) so the other routes keep using the Redis limiter by default.
 */
public class LocalRateLimiter extends AbstractRateLimiter<LocalRateLimiter.Config> {

    public static final String CONFIGURATION_PROPERTY_NAME = "local-rate-limiter";
    private static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    private static final String REPLENISH_RATE_HEADER = "X-RateLimit-Replenish-Rate";
    private static final String BURST_CAPACITY_HEADER = "X-RateLimit-Burst-Capacity";
    private static final String REQUESTED_TOKENS_HEADER = "X-RateLimit-Requested-Tokens";
    // Buckets that are full again (client idle) are dropped once there are this many.
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Config defaultConfig = new Config();

    public LocalRateLimiter(ConfigurationService configurationService) {
        super(Config.class, CONFIGURATION_PROPERTY_NAME, configurationService);
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        Config config = getConfig().getOrDefault(routeId, defaultConfig);
        // Same per-client key as RedisRateLimiter (client id only, not the route).
        Bucket bucket = buckets.computeIfAbsent(id, key -> new Bucket(config.getBurstCapacity()));
        long nowNanos = System.nanoTime();
        long remaining;
        boolean allowed;
        synchronized (bucket) {
            double elapsedSeconds = Math.max(0, nowNanos - bucket.lastRefillNanos) / 1_000_000_000.0;
            double tokens = Math.min(config.getBurstCapacity(),
                    bucket.tokens + elapsedSeconds * config.getReplenishRate());
            allowed = tokens >= config.getRequestedTokens();
            if (allowed) {
                tokens -= config.getRequestedTokens();
            }
            bucket.tokens = tokens;
            bucket.lastRefillNanos = nowNanos;
            remaining = (long) tokens;
        }
        if (buckets.size() > CLEANUP_THRESHOLD) {
            cleanup(config, nowNanos);
        }
        return Mono.just(new Response(allowed, headers(config, remaining)));
    }

    private void cleanup(Config config, long nowNanos) {
        double secondsToFill = config.getReplenishRate() <= 0 ? Double.MAX_VALUE
                : (double) config.getBurstCapacity() / config.getReplenishRate();
        buckets.entrySet().removeIf(e -> (nowNanos - e.getValue().lastRefillNanos) / 1_000_000_000.0 > secondsToFill + 60);
    }

    private Map<String, String> headers(Config config, long remaining) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(REMAINING_HEADER, Long.toString(remaining));
        headers.put(REPLENISH_RATE_HEADER, Integer.toString(config.getReplenishRate()));
        headers.put(BURST_CAPACITY_HEADER, Integer.toString(config.getBurstCapacity()));
        headers.put(REQUESTED_TOKENS_HEADER, Integer.toString(config.getRequestedTokens()));
        return headers;
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        private Bucket(int capacity) {
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }
    }

    /** Same settings (and defaults) as RedisRateLimiter.Config. */
    public static class Config {
        private int replenishRate = 1;
        private int burstCapacity = 1;
        private int requestedTokens = 1;

        public int getReplenishRate() {
            return replenishRate;
        }

        public Config setReplenishRate(int replenishRate) {
            this.replenishRate = replenishRate;
            return this;
        }

        public int getBurstCapacity() {
            return burstCapacity;
        }

        public Config setBurstCapacity(int burstCapacity) {
            this.burstCapacity = burstCapacity;
            return this;
        }

        public int getRequestedTokens() {
            return requestedTokens;
        }

        public Config setRequestedTokens(int requestedTokens) {
            this.requestedTokens = requestedTokens;
            return this;
        }
    }
}
