package com.se.frms.notification.service;

import com.se.frms.notification.dto.AdminNotificationRecipient;
import java.util.List;

public interface NotificationRecipientCacheService {
    void refreshRecipients();
    List<AdminNotificationRecipient> getCachedRecipients();
}
