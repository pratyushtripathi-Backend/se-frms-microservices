package com.se.frms.fraudengine.dto;

/**
 * Simplified, city/country-level geocoding result resolved from a transaction's
 * latitude/longitude via Geoapify's Reverse Geocoding API. Deliberately does NOT carry the
 * full formatted address, since matching against the location blacklist is done
 * at city/country level (the same coordinates can otherwise produce differently
 * worded full addresses depending on exact position within a city).
 */
public record GeocodeResult(String city, String country) {

    public String resolvedLocation() {
        if (city == null && country == null) {
            return null;
        }
        if (city == null) {
            return country;
        }
        if (country == null) {
            return city;
        }
        return city + ", " + country;
    }
}
