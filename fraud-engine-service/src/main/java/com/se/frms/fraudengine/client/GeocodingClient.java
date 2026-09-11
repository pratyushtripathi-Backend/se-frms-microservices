package com.se.frms.fraudengine.client;

import com.se.frms.fraudengine.dto.GeocodeResult;
import com.se.frms.fraudengine.dto.GeoapifyProperties;
import com.se.frms.fraudengine.dto.GeoapifyReverseGeocodeResponse;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Calls Geoapify's Reverse Geocoding API (lat/long -> address) to resolve a
 * transaction's coordinates down to city/country level, for location-blacklist
 * matching. This is the ONLY place in the fraud-evaluation path that makes a live
 * external network call - everything else (rules, IP/device blacklist) is served
 * from in-memory caches. Failures (missing API key, network issue, no result) are
 * swallowed and treated as "location unresolved" so a Geoapify outage never blocks
 * fraud evaluation - the transaction is simply evaluated without location-blacklist
 * matching for that one request.
 *
 * Chosen over Google Maps Geocoding API because it does not require a billing
 * account/credit card to obtain an API key, while still offering a documented
 * free tier (3,000 credits/day) and a documented rate limit (5 requests/second)
 * suitable for backend/server-side production use.
 *
 * Called with format=json, which returns a flat "results" array (confirmed via a
 * live test call) rather than the GeoJSON "features"/"properties" shape some of
 * Geoapify's other endpoints use.
 */
@Component
@Slf4j
public class GeocodingClient {

    // Geoapify's API is always external/public - never routed through Eureka load-balancing.
    private final RestClient.Builder directRestClientBuilder;

    @Value("${geoapify.geocoding.base-url:https://api.geoapify.com/v1/geocode/reverse}")
    private String geocodingBaseUrl;

    @Value("${geoapify.geocoding.api-key:}")
    private String apiKey;

    public GeocodingClient(@Qualifier("directRestClientBuilder") RestClient.Builder directRestClientBuilder) {
        this.directRestClientBuilder = directRestClientBuilder;
    }

    public Optional<GeocodeResult> reverseGeocode(BigDecimal latitude, BigDecimal longitude) {

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Geoapify Geocoding API key not configured (geoapify.geocoding.api-key) - skipping location resolution");
            return Optional.empty();
        }

        try {

            String url = geocodingBaseUrl
                    + "?lat=" + latitude
                    + "&lon=" + longitude
                    + "&format=json"
                    + "&apiKey=" + apiKey;

            GeoapifyReverseGeocodeResponse response = directRestClientBuilder.build()
                    .get()
                    .uri(url)
                    .retrieve()
                    .body(GeoapifyReverseGeocodeResponse.class);

            return parseResult(latitude, longitude, response);

        } catch (RestClientException ex) {

            log.warn(
                    "Geoapify Geocoding API call failed for lat={}, lng={}: {}",
                    latitude,
                    longitude,
                    ex.getMessage()
            );

            return Optional.empty();
        }
    }

    private Optional<GeocodeResult> parseResult(
            BigDecimal latitude,
            BigDecimal longitude,
            GeoapifyReverseGeocodeResponse response
    ) {

        if (response == null
                || response.results() == null
                || response.results().isEmpty()) {

            log.debug(
                    "Geoapify Geocoding returned no usable result for lat={}, lng={}",
                    latitude,
                    longitude
            );

            return Optional.empty();
        }

        GeoapifyProperties topResult = response.results().get(0);

        String city = topResult.city();
        String country = topResult.country();

        if (city == null && country == null) {
            return Optional.empty();
        }

        return Optional.of(new GeocodeResult(city, country));
    }
}