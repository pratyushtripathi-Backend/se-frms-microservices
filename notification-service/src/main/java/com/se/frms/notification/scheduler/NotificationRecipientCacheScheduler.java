package com.se.frms.notification.scheduler;

import com.se.frms.notification.service.NotificationRecipientCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationRecipientCacheScheduler {

    private final NotificationRecipientCacheService recipientCacheService;

    @Scheduled(
            initialDelayString = "${notification.recipients.sync.initial-delay-ms:10000}",
            fixedDelayString = "${notification.recipients.sync.fixed-delay-ms:300000}"
    )
    public void refreshRecipients() {
        recipientCacheService.refreshRecipients();
    }
}
