package com.se.frms.notification.client;

import com.se.frms.notification.dto.AiDraftTemplateRequest;
import com.se.frms.notification.dto.AiDraftTemplateResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Asks ai-service to draft a notification template, for the admin's
 * "AI-assisted drafting" affordance on the template management screen.
 * Uses RestTemplate (not RestClient) - this service's own existing
 * outbound-call convention, matched instead of introducing a different HTTP
 * client just for this one call.
 */
@Component
@Slf4j
public class AiDraftTemplateClient {

    private final RestTemplate aiRestTemplate;

    public AiDraftTemplateClient(@Qualifier("aiRestTemplate") RestTemplate aiRestTemplate) {
        this.aiRestTemplate = aiRestTemplate;
    }

    @Value("${frms.ai.base-url:http://localhost:8101}")
    private String aiBaseUrl;

    @Value("${frms.ai.path:/api/v1/ai}")
    private String aiPath;

    public String draftTemplate(String intent, String channel) {
        AiDraftTemplateRequest request = new AiDraftTemplateRequest(intent, channel);
        try {
            AiDraftTemplateResponse response = aiRestTemplate.postForObject(
                    aiBaseUrl + aiPath + "/draft-template", request, AiDraftTemplateResponse.class);
            return response == null ? null : response.draft();
        } catch (RestClientException ex) {
            log.warn("AI draft-template call failed for intent={}, channel={}", intent, channel, ex);
            throw new IllegalStateException("Unable to draft template via ai-service", ex);
        }
    }
}
