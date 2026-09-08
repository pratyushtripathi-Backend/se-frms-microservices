package com.se.frms.fraudengine.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Shape of Geoapify's Reverse Geocoding API response when called with
 * format=json - a flat "results" array (NOT the GeoJSON "features"/"properties"
 * shape used by other Geoapify endpoints/format options). Confirmed against a
 * live API call before relying on it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeoapifyReverseGeocodeResponse(
        List<GeoapifyProperties> results
) {
}
