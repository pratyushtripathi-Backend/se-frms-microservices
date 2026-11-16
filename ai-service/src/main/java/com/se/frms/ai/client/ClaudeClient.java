package com.se.frms.ai.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.se.frms.ai.exception.ClaudeCallException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Thin wrapper around the Claude Messages API - the only place in this
 * service (and the only place in the whole FRMS platform) that calls an
 * external LLM. Callers pass an already-redacted prompt; this class never
 * sees or logs the raw, unredacted payload.
 */
@Component
@Slf4j
public class ClaudeClient {

    private static final String MESSAGES_PATH = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final RestClient restClient;

    @Value("${ai.anthropic.api-key:}")
    private String apiKey;

    @Value("${ai.anthropic.model:claude-sonnet-5-5}")
    private String model;

    @Value("${ai.anthropic.max-tokens:1024}")
    private int maxTokens;

    public ClaudeClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    /** Sends one user-turn prompt, returns the concatenated text of the response. */
    public String complete(String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ClaudeCallException("ai.anthropic.api-key is not configured");
        }
        ClaudeRequest request = new ClaudeRequest(model, maxTokens,
                List.of(new ClaudeMessage("user", prompt)));
        try {
            ClaudeResponse response = restClient.post()
                    .uri(MESSAGES_PATH)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("content-type", "application/json")
                    .body(request)
                    .retrieve()
                    .body(ClaudeResponse.class);
            if (response == null || response.content() == null || response.content().isEmpty()) {
                throw new ClaudeCallException("Claude API returned an empty response");
            }
            StringBuilder text = new StringBuilder();
            for (ClaudeContentBlock block : response.content()) {
                if (block.text() != null) {
                    text.append(block.text());
                }
            }
            return text.toString();
        } catch (RestClientException ex) {
            log.warn("Claude API call failed", ex);
            throw new ClaudeCallException("Claude API call failed", ex);
        }
    }

    private record ClaudeRequest(
            String model,
            @JsonProperty("max_tokens") int maxTokens,
            List<ClaudeMessage> messages
    ) {
    }

    private record ClaudeMessage(String role, String content) {
    }

    private record ClaudeResponse(List<ClaudeContentBlock> content) {
    }

    private record ClaudeContentBlock(String type, String text) {
    }
}
