package com.se.frms.notification.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.se.frms.notification.dto.Msg24x7MessageStatusResponse;
import com.se.frms.notification.dto.Msg24x7SmsRequest;
import com.se.frms.notification.dto.Msg24x7SmsResponse;
import com.se.frms.notification.dto.SmsDeliveryStatus;
import com.se.frms.notification.service.SmsSenderService;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
@Slf4j
public class Msg24x7SmsSenderService implements SmsSenderService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${notification.sms.msg24x7.send-url}") private String sendUrl;
    @Value("${notification.sms.msg24x7.status-url}") private String statusUrl;
    @Value("${notification.sms.msg24x7.api-key}") private String apiKey;
    @Value("${notification.sms.msg24x7.client-id}") private String clientId;
    @Value("${notification.sms.msg24x7.sender-id}") private String senderId;

    @Override
    public String send(String phoneNumber, String message, String templateId, String correlationId) {
        // templateId/correlationId are no longer sent to MSG24x7 - confirmed with
        // their team that only these 5 fields are needed, and the extra fields
        // (templateId, principleEntityId, isUnicode/isFlash, coRelator, etc.) were
        // accepted by the API but the SMS never actually reached the phone.
        // Kept as method parameters so callers (NotificationServiceImpl) don't
        // need to change; templateId is still used elsewhere to pick the
        // REVIEW/BLOCK message text.
        validateConfig();
        Msg24x7SmsRequest request = Msg24x7SmsRequest.builder()
                .senderId(senderId)
                .message(message)
                .mobileNumbers(normalizePhoneNumber(phoneNumber))
                .apiKey(apiKey)
                .clientId(clientId)
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN));
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    sendUrl, HttpMethod.POST, new HttpEntity<>(request, headers), String.class);
            Msg24x7SmsResponse body = objectMapper.readValue(response.getBody(), Msg24x7SmsResponse.class);
            if (!body.isSuccess()) {
                throw new IllegalStateException("MSG24x7 rejected the SMS request: " + body.getErrorDescription());
            }
            return body.getData() == null || body.getData().isEmpty()
                    ? null
                    : body.getData().get(0).getMessageId();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to send SMS", ex);
        }
    }

    /**
     * GET /api/v2/MessageStatus - a best-effort lookup. Any transport/parse
     * failure or a not-yet-final provider status is reported back as "pending"
     * (delivered()==false, failed()==false) rather than thrown, so the caller's
     * retry loop simply checks again later instead of giving up.
     */
    @Override
    public SmsDeliveryStatus checkDeliveryStatus(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return new SmsDeliveryStatus("UNKNOWN", "No MessageId to check");
        }
        try {
            // Built as a URI (not a plain String) with an explicit .encode() call -
            // apiKey is a base64-shaped value containing '/' and a trailing '=';
            // left un-encoded in a query string, MSG24x7 rejected every call with
            // "Invalid ApiCredentials" even though the exact same key works fine
            // in SendSMS's JSON body (a JSON string value never needs URL-encoding,
            // so this only ever showed up here, on the query-string call).
            URI uri = UriComponentsBuilder.fromHttpUrl(statusUrl)
                    .queryParam("ApiKey", apiKey)
                    .queryParam("ClientId", clientId)
                    .queryParam("MessageId", messageId)
                    .build()
                    .encode()
                    .toUri();
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN));
            ResponseEntity<String> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            Msg24x7MessageStatusResponse body =
                    objectMapper.readValue(response.getBody(), Msg24x7MessageStatusResponse.class);
            if (!body.isSuccess()) {
                log.warn("MessageStatus lookup returned an error, messageId={}, description={}",
                        messageId, body.getErrorDescription());
                return new SmsDeliveryStatus("PENDING", body.getErrorDescription());
            }
            String status = body.getData().getStatus();
            return new SmsDeliveryStatus(status, "Carrier status: " + status);
        } catch (Exception ex) {
            log.warn("MessageStatus lookup failed, messageId={}", messageId, ex);
            return new SmsDeliveryStatus("PENDING", "Status check call failed: " + ex.getMessage());
        }
    }

    private void validateConfig() {
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(clientId) || !StringUtils.hasText(senderId)) {
            throw new IllegalStateException("MSG24x7 SMS configuration is missing");
        }
    }

    private String normalizePhoneNumber(String phoneNumber) {
        String digits = phoneNumber == null ? "" : phoneNumber.replaceAll("\\D", "");
        if (digits.length() == 10) return "91" + digits;
        if (digits.length() == 12 && digits.startsWith("91")) return digits;
        throw new IllegalArgumentException("Invalid Indian mobile number");
    }
}
