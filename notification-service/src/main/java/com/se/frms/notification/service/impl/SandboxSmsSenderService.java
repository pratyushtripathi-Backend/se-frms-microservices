package com.se.frms.notification.service.impl;

import com.se.frms.notification.dto.SmsDeliveryStatus;
import com.se.frms.notification.service.SmsSenderService;
import java.util.Locale;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
        prefix = "notification.sms",
        name = "mode",
        havingValue = "sandbox"
)
@Slf4j
public class SandboxSmsSenderService implements SmsSenderService {

    private final String behavior;

    public SandboxSmsSenderService(
            @Value("${notification.sms.sandbox.behavior:success}") String behavior) {

        this.behavior = behavior == null
                ? "success"
                : behavior.trim().toLowerCase(Locale.ROOT);

        if (!"success".equals(this.behavior)
                && !"failure".equals(this.behavior)
                && !"timeout".equals(this.behavior)) {
            throw new IllegalArgumentException(
                    "notification.sms.sandbox.behavior must be success, failure, or timeout"
            );
        }
    }

    @Override
    public String send(
            String phoneNumber,
            String message,
            String templateId,
            String correlationId) {

        // No MSG24x7 / MSG91 HTTP API request is made in this class.

        if ("failure".equals(behavior)) {
            log.warn(
                    "SANDBOX SMS simulated provider failure: correlationId={}, recipient={}",
                    correlationId,
                    phoneNumber
            );
            throw new IllegalStateException("SANDBOX SMS simulated provider failure");
        }

        if ("timeout".equals(behavior)) {
            log.warn(
                    "SANDBOX SMS simulated provider timeout: correlationId={}, recipient={}",
                    correlationId,
                    phoneNumber
            );
            throw new IllegalStateException("SANDBOX SMS simulated provider timeout");
        }

        String messageId = "SANDBOX-" + UUID.randomUUID();

        log.info(
                "SANDBOX SMS accepted; no provider API call made: correlationId={}, recipient={}, messageId={}",
                correlationId,
                phoneNumber,
                messageId
        );

        return messageId;
    }

    @Override
    public SmsDeliveryStatus checkDeliveryStatus(String messageId) {
        if (messageId == null || !messageId.startsWith("SANDBOX-")) {
            return new SmsDeliveryStatus(
                    "UNKNOWN",
                    "Not a sandbox message ID"
            );
        }

        log.info(
                "SANDBOX SMS delivery simulated as delivered; no provider API call made: messageId={}",
                messageId
        );

        return new SmsDeliveryStatus(
                "SANDBOX_DELIVERED",
                "Simulated sandbox delivery"
        );
    }

    @Override
    public boolean isSandbox() {
        return true;
    }
}