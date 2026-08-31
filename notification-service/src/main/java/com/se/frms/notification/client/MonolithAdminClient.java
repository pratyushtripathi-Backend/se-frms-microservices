package com.se.frms.notification.client;

import com.se.frms.notification.dto.AdminNotificationRecipient;
import com.se.frms.notification.dto.MonolithApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
public class MonolithAdminClient {

    private final RestTemplate restTemplate;

    @Value("${notification.monolith.base-url}")
    private String monolithBaseUrl;

    @Value("${notification.monolith.internal-api-key}")
    private String internalApiKey;

    public List<AdminNotificationRecipient> fetchActiveAdminRecipients() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-INTERNAL-API-KEY", internalApiKey);

        ResponseEntity<MonolithApiResponse<List<AdminNotificationRecipient>>> response =
                restTemplate.exchange(
                        monolithBaseUrl + "/api/v1/admin/internal/notification-recipients",
                        HttpMethod.GET,
                        new HttpEntity<Void>(headers),
                        new ParameterizedTypeReference<>() { }
                );

        MonolithApiResponse<List<AdminNotificationRecipient>> body = response.getBody();
        if (body == null || !Boolean.TRUE.equals(body.status())) {
            throw new IllegalStateException("Could not fetch active admin notification recipients");
        }
        return body.responseData() == null ? List.of() : body.responseData();
    }
}
