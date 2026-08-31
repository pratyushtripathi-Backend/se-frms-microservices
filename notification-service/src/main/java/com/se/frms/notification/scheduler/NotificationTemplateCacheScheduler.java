package com.se.frms.notification.scheduler;

import com.se.frms.notification.service.NotificationTemplateCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationTemplateCacheScheduler {
    private final NotificationTemplateCacheService notificationTemplateCacheService;

    @Scheduled(
            initialDelayString = "${notification.templates.sync.initial-delay-ms:2000}",
            fixedDelayString = "${notification.templates.sync.fixed-delay-ms:300000}"
    )
    public void refreshTemplates() {
        notificationTemplateCacheService.refreshEmailTemplates();
    }
}
