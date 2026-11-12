package com.se.frms.notification.config;

import jakarta.annotation.PostConstruct;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SmsModeConfiguration {

    @Value("${notification.sms.mode:}")
    private String mode;

    @PostConstruct
    void validateMode() {
        String normalized = mode == null
                ? ""
                : mode.trim().toLowerCase(Locale.ROOT);

        if (!"sandbox".equals(normalized)
                && !"production".equals(normalized)) {
            throw new IllegalStateException(
                    "notification.sms.mode must be set to 'sandbox' or 'production'"
            );
        }
    }
}