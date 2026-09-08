package com.se.frms.fraudengine.cache;

import com.se.frms.fraudengine.client.GeocodingClient;
import com.se.frms.fraudengine.dto.GeocodeResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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

    private final Map<String, Optional<GeocodeResult>> cache = new ConcurrentHashMap<>();

    public Optional<GeocodeResult> resolve(BigDecimal latitude, BigDecimal longitude) {

        if (latitude == null || longitude == null) {
            return Optional.empty();
        }

        String key = roundedKey(latitude, longitude);

        return cache.computeIfAbsent(key, ignored -> {

            Optional<GeocodeResult> result = geocodingClient.reverseGeocode(latitude, longitude);

            log.info(
                    "Geocode resolved lat={}, lng={}, resolved={}",
                    latitude,
                    longitude,
                    result.map(GeocodeResult::resolvedLocation).orElse("UNRESOLVED")
            );

            return result;
        });
    }

    private String roundedKey(BigDecimal latitude, BigDecimal longitude) {

        BigDecimal roundedLatitude = latitude.setScale(ROUND_SCALE, RoundingMode.HALF_UP);
        BigDecimal roundedLongitude = longitude.setScale(ROUND_SCALE, RoundingMode.HALF_UP);

        return roundedLatitude + "," + roundedLongitude;
    }
}
