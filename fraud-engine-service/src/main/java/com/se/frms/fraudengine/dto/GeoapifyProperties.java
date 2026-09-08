package com.se.frms.fraudengine.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GeoapifyProperties(
        String city,
        String country,
        @JsonProperty("country_code") String countryCode,
        String formatted
) {
}
