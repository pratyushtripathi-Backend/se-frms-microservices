package com.se.frms.notification.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.se.frms.notification.dto.Msg24x7SmsRequest;
import com.se.frms.notification.dto.Msg24x7SmsResponse;
import com.se.frms.notification.service.SmsSenderService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

@Service
@RequiredArgsConstructor
public class Msg24x7SmsSenderService implements SmsSenderService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${notification.sms.msg24x7.send-url}") private String sendUrl;
    @Value("${notification.sms.msg24x7.api-key}") private String apiKey;
    @Value("${notification.sms.msg24x7.client-id}") private String clientId;
    @Value("${notification.sms.msg24x7.sender-id}") private String senderId;
    @Value("${notification.sms.msg24x7.principle-entity-id}") private String principleEntityId;

    @Override
    public void send(String phoneNumber, String message, String templateId, String correlationId) {
        validateConfig(templateId);
        Msg24x7SmsRequest request = Msg24x7SmsRequest.builder()
                .senderId(senderId).isUnicode(false).isFlash(false).isRegisteredForDelivery(true)
                .validityPeriod("").dataCoding(0).schedTime("").groupId("")
                .message(message).mobileNumbers(normalizePhoneNumber(phoneNumber)).serviceId("")
                .coRelator(correlationId).linkId("").principleEntityId(principleEntityId)
                .templateId(templateId).apiKey(apiKey).clientId(clientId).build();
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
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to send SMS", ex);
        }
    }

    private void validateConfig(String templateId) {
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(clientId)
                || !StringUtils.hasText(senderId) || !StringUtils.hasText(principleEntityId)
                || !StringUtils.hasText(templateId)) {
            throw new IllegalStateException("MSG24x7 SMS configuration or approved DLT template ID is missing");
        }
    }

    private String normalizePhoneNumber(String phoneNumber) {
        String digits = phoneNumber == null ? "" : phoneNumber.replaceAll("\\D", "");
        if (digits.length() == 10) return "91" + digits;
        if (digits.length() == 12 && digits.startsWith("91")) return digits;
        throw new IllegalArgumentException("Invalid Indian mobile number");
    }
}
