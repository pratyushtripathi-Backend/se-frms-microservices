package com.se.frms.rulecache.client;

import com.se.frms.rulecache.dto.MonolithApiResponseDTO;
import com.se.frms.rulecache.dto.DecisionPolicyCacheSyncResponseDTO;
import com.se.frms.rulecache.dto.RuleCacheSyncResponseDTO;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Component
@RequiredArgsConstructor
public class MonolithRuleClient {

    private final RestTemplate restTemplate;

    @Value("${frms.monolith.base-url}")
    private String monolithBaseUrl;

    @Value("${frms.monolith.internal-api-key}")
    private String internalApiKey;

    public List<RuleCacheSyncResponseDTO> fetchActiveRules() {

        String url =
                monolithBaseUrl
                        + "/api/v1/internal/rule-cache/active-rules";

        HttpHeaders headers =
                new HttpHeaders();

        headers.set(
                "X-INTERNAL-API-KEY",
                internalApiKey
        );

        HttpEntity<Void> requestEntity =
                new HttpEntity<>(headers);

        ResponseEntity<MonolithApiResponseDTO<List<RuleCacheSyncResponseDTO>>> response =
                restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        requestEntity,
                        new ParameterizedTypeReference<>() {
                        }
                );

        MonolithApiResponseDTO<List<RuleCacheSyncResponseDTO>> body =
                response.getBody();

        if (body == null || !Boolean.TRUE.equals(body.getStatus())) {
            throw new RuntimeException("Failed to fetch active rules from monolith");
        }

        if (body.getResponseData() == null) {
            return List.of();
        }

        return body.getResponseData();
    }

    public DecisionPolicyCacheSyncResponseDTO fetchActiveDecisionPolicy() {

        String url =
                monolithBaseUrl
                        + "/api/v1/internal/rule-cache/active-decision-policy";

        HttpHeaders headers =
                new HttpHeaders();

        headers.set(
                "X-INTERNAL-API-KEY",
                internalApiKey
        );

        HttpEntity<Void> requestEntity =
                new HttpEntity<>(headers);

        ResponseEntity<MonolithApiResponseDTO<DecisionPolicyCacheSyncResponseDTO>> response =
                restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        requestEntity,
                        new ParameterizedTypeReference<>() {
                        }
                );

        MonolithApiResponseDTO<DecisionPolicyCacheSyncResponseDTO> body =
                response.getBody();

        if (body == null || !Boolean.TRUE.equals(body.getStatus())) {
            throw new RuntimeException("Failed to fetch active decision policy from monolith");
        }

        return body.getResponseData();
    }
}
