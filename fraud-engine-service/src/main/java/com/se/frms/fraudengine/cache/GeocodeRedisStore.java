package com.se.frms.fraudengine.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.se.frms.fraudengine.dto.GeocodeResult;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis layer of GeocodeCache: keeps definitive geocoding answers (a resolved
 * city/country, or "Geoapify has no result here") so they survive restarts and are
 * shared by every fraud-engine instance. Fewer Geoapify calls (5 req/s, 3,000/day
 * limits) and ~1 ms instead of 100-400 ms for a location already resolved before.
 *
 * Purely an optimisation and never required:
 *  - transient failures (timeout / network / HTTP error) are never written here;
 *  - any Redis error is logged once and Redis is then skipped for
 *    SKIP_AFTER_ERROR (30 s), so an unavailable Redis costs at most one short
 *    timeout instead of slowing every evaluation - lookups then go to Geoapify
 *    exactly as before this class existed.
 *
 * Stored value: {"city":"..","country":".."} for a resolved location, or
 * NO_RESULT for "Geoapify has no result for these coordinates".
 */
@Component
@Slf4j
public class GeocodeRedisStore {

    private static final String NO_RESULT = "NONE";
    private static final long SKIP_AFTER_ERROR_NANOS = Duration.ofSeconds(30).toNanos();

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final Duration ttl;

    // System.nanoTime() until which Redis is skipped after an error (0 = not skipping).
    private volatile long skipUntilNanos = 0;

    public GeocodeRedisStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${frms.geocode.redis.key-prefix:frms:geocode:}") String keyPrefix,
            @Value("${frms.geocode.redis.ttl-days:30}") long ttlDays
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.keyPrefix = keyPrefix;
        this.ttl = Duration.ofDays(Math.max(ttlDays, 1));
    }

    /**
     * Outer Optional empty = not in Redis (or Redis unavailable): ask Geoapify.
     * Inner Optional = the stored definitive answer (empty = "no result here").
     */
    public Optional<Optional<GeocodeResult>> get(String key) {
        if (skipping()) {
            return Optional.empty();
        }
        try {
            String value = redisTemplate.opsForValue().get(keyPrefix + key);
            if (value == null) {
                return Optional.empty();
            }
            if (NO_RESULT.equals(value)) {
                return Optional.of(Optional.empty());
            }
            return Optional.of(Optional.of(objectMapper.readValue(value, GeocodeResult.class)));
        } catch (Exception ex) {
            onError("read", ex);
            return Optional.empty();
        }
    }

    /** Stores a definitive answer (never call this for a failed/timed-out lookup). */
    public void put(String key, Optional<GeocodeResult> result) {
        if (skipping()) {
            return;
        }
        try {
            String value = result.isPresent() ? objectMapper.writeValueAsString(result.get()) : NO_RESULT;
            redisTemplate.opsForValue().set(keyPrefix + key, value, ttl);
        } catch (Exception ex) {
            onError("write", ex);
        }
    }

    private boolean skipping() {
        long until = skipUntilNanos;
        return until != 0 && System.nanoTime() - until < 0;
    }

    private void onError(String operation, Exception ex) {
        boolean firstError = !skipping();
        long until = System.nanoTime() + SKIP_AFTER_ERROR_NANOS;
        skipUntilNanos = until == 0 ? 1 : until;
        if (firstError) {
            log.warn("Geocode Redis cache {} failed - skipping Redis for 30 s, using Geoapify directly: {}",
                    operation, ex.getMessage());
        }
    }
}
