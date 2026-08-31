package com.se.frms.notification.service;

import com.se.frms.notification.dto.CreateNotificationTemplateRequest;
import com.se.frms.notification.dto.NotificationTemplateResponse;
import com.se.frms.notification.dto.UpdateNotificationTemplateRequest;
import com.se.frms.notification.dto.UpdateNotificationTemplateStatusRequest;
import java.util.List;
import java.util.UUID;

public interface NotificationTemplateManagementService {
    List<NotificationTemplateResponse> getTemplates();
    NotificationTemplateResponse getTemplateById(UUID templateId);
    NotificationTemplateResponse createTemplate(CreateNotificationTemplateRequest request);
    NotificationTemplateResponse updateTemplate(UUID templateId, UpdateNotificationTemplateRequest request);
    NotificationTemplateResponse updateTemplateStatus(UUID templateId, UpdateNotificationTemplateStatusRequest request);
    int refreshTemplateCache();
}
