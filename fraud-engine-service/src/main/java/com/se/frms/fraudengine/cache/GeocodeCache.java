package com.se.frms.fraudengine.cache;

import com.se.frms.fraudengine.client.GeocodingClient;
import com.se.frms.fraudengine.dto.GeocodeResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * In-memory cache in front of GeocodingClient, so repeat/nearby transactions do not
 * each cost a fresh Geoapify Geocoding API call (Geoapify's free tier is capped at
 * 3,000 requests/day).
 *
 * Coordinates are rounded to 3 decimal places (~111m) before being used as the cache
 * key - since location-blacklist matching only cares about city/country level, exact
 * lat/long precision is not needed here, and rounding lets nearby transactions in the
 * same city share one cached lookup instead of each triggering a new API call.
 *
 * Only definitive answers are kept for good: a resolved location, or a genuine "Geoapify
 * has no result for these coordinates". A transient failure (timeout, network error, HTTP
 * error) is remembered only briefly (geoapify.geocoding.failure-retry-ms, default 60s) -
 * long enough that an outage does not make every transaction wait for the timeout again,
 * short enough that location-blacklist matching for that coordinate comes back by itself
 * instead of staying off until a restart.
 *
 * This cache lives only in memory (like ActiveRuleCache/ActiveBlacklistCache) and is
 * unbounded/never expires for now - a city's location basically never changes, so
 * there is no correctness reason to evict entries; if memory growth ever becomes a
 * concern (very large numbers of distinct coordinates), an eviction policy can be
 * added later without changing the matching behaviour.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GeocodeCache {

    private static final int ROUND_SCALE = 3;

    private final GeocodingClient geocodingClient;

    @Value("${geoapify.geocoding.failure-retry-ms:60000}")
    private long failureRetryMs;

    // expiresAtNanos == 0 means "keep for good".
    private record CachedLookup(Optional<GeocodeResult> result, long expiresAtNanos) {
        boolean isUsable(long nowNanos) {
            return expiresAtNanos == 0 || nowNanos - expiresAtNanos < 0;
        }
    }

    private final Map<String, CachedLookup> cache = new ConcurrentHashMap<>();

    // Lookups currently in progress, one per coordinate key. Concurrent first
    // requests for the same coordinate wait on the same future, so it is still one
    // Geoapify call - but the call is no longer made inside ConcurrentHashMap.compute(),
    // which held a bin lock for up to the read timeout (1.5 s) and could block
    // lookups of OTHER coordinates (even already-cached ones) that hash to the same bin.
    private final Map<String, CompletableFuture<CachedLookup>> inFlight = new ConcurrentHashMap<>();

    public Optional<GeocodeResult> resolve(BigDecimal latitude, BigDecimal longitude) {

        if (latitude == null || longitude == null) {
            return Optional.empty();
        }

        String key = roundedKey(latitude, longitude);

        // Fast path: cached answer, no locking at all.
        CachedLookup existing = cache.get(key);
        if (existing != null && existing.isUsable(System.nanoTime())) {
            return existing.result();
        }

        CompletableFuture<CachedLookup> mine = new CompletableFuture<>();
        CompletableFuture<CachedLookup> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            // Another thread is already looking this coordinate up - wait for its answer.
            try {
                return running.join().result();
            } catch (RuntimeException ex) {
                return Optional.empty();
            }
        }

        try {
            // Re-check: the previous lookup may have finished between get() and putIfAbsent().
            CachedLookup latest = cache.get(key);
            if (latest != null && latest.isUsable(System.nanoTime())) {
                mine.complete(latest);
                return latest.result();
            }
            CachedLookup fresh = lookup(latitude, longitude);
            cache.put(key, fresh);
            mine.complete(fresh);
            return fresh.result();
        } catch (RuntimeException ex) {
            mine.completeExceptionally(ex);
            throw ex;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    /** One Geoapify call, bounded by the client's connect/read timeouts. */
    private CachedLookup lookup(BigDecimal latitude, BigDecimal longitude) {
        GeocodingClient.Lookup lookup = geocodingClient.lookup(latitude, longitude);

        log.info(
                "Geocode resolved lat={}, lng={}, resolved={}",
                latitude,
                longitude,
                lookup.failed()
                        ? "FAILED (will retry)"
                        : lookup.result().map(GeocodeResult::resolvedLocation).orElse("UNRESOLVED")
        );

        long expiresAt = 0;
        if (lookup.failed()) {
            // nanoTime can legitimately be 0; keep 0 reserved for "forever".
            expiresAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(failureRetryMs, 1));
            if (expiresAt == 0) {
                expiresAt = 1;
            }
        }
        return new CachedLookup(lookup.result(), expiresAt);
    }

    private String roundedKey(BigDecimal latitude, BigDecimal longitude) {

        BigDecimal roundedLatitude = latitude.setScale(ROUND_SCALE, RoundingMode.HALF_UP);
        BigDecimal roundedLongitude = longitude.setScale(ROUND_SCALE, RoundingMode.HALF_UP);

        return roundedLatitude + "," + roundedLongitude;
    }
}
