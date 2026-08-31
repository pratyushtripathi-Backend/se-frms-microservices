package com.se.frms.notification.service;

import com.se.frms.notification.dto.EmailTemplateContent;

public interface NotificationTemplateCacheService {
    void refreshEmailTemplates();

    void evictEmailTemplate(String templateCode);

    EmailTemplateContent getEmailTemplate(String fraudDecision);
}
