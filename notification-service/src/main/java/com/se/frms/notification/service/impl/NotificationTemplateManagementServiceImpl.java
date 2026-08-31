package com.se.frms.notification.service.impl;

import com.se.frms.notification.dto.CreateNotificationTemplateRequest;
import com.se.frms.notification.dto.NotificationTemplateResponse;
import com.se.frms.notification.dto.UpdateNotificationTemplateRequest;
import com.se.frms.notification.dto.UpdateNotificationTemplateStatusRequest;
import com.se.frms.notification.entity.NotificationTemplate;
import com.se.frms.notification.repository.NotificationTemplateRepository;
import com.se.frms.notification.service.NotificationTemplateCacheService;
import com.se.frms.notification.service.NotificationTemplateManagementService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class NotificationTemplateManagementServiceImpl implements NotificationTemplateManagementService {
    private static final String EMAIL = "EMAIL";
    private static final String SYSTEM_USER = "NOTIFICATION_SERVICE";

    private final NotificationTemplateRepository notificationTemplateRepository;
    private final NotificationTemplateCacheService notificationTemplateCacheService;

    @Override
    @Transactional(readOnly = true)
    public List<NotificationTemplateResponse> getTemplates() {
        return notificationTemplateRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationTemplateResponse getTemplateById(UUID templateId) {
        return toResponse(findTemplate(templateId));
    }

    @Override
    @Transactional
    public NotificationTemplateResponse createTemplate(CreateNotificationTemplateRequest request) {
        String decision = normalizeSupportedDecision(request.fraudDecision());
        String templateCode = templateCode(decision);
        if (notificationTemplateRepository.existsByTemplateCode(templateCode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Template already exists for fraud decision: " + decision);
        }

        LocalDateTime now = LocalDateTime.now();
        NotificationTemplate template = new NotificationTemplate();
        template.setTemplateCode(templateCode);
        template.setNotificationType(EMAIL);
        template.setFraudDecision(decision);
        template.setSubjectTemplate(request.subjectTemplate().trim());
        template.setBodyTemplate(request.bodyTemplate().trim());
        template.setStatus(request.status());
        template.setCreatedBy(SYSTEM_USER);
        template.setCreatedDate(now);
        template.setUpdatedAt(now);

        NotificationTemplate saved = notificationTemplateRepository.save(template);
        refreshCacheAfterCommit();
        return toResponse(saved);
    }

    @Override
    @Transactional
    public NotificationTemplateResponse updateTemplate(UUID templateId, UpdateNotificationTemplateRequest request) {
        NotificationTemplate template = findTemplate(templateId);
        template.setSubjectTemplate(request.subjectTemplate().trim());
        template.setBodyTemplate(request.bodyTemplate().trim());
        template.setUpdatedAt(LocalDateTime.now());
        NotificationTemplate saved = notificationTemplateRepository.save(template);
        refreshCacheAfterCommit();
        return toResponse(saved);
    }

    @Override
    @Transactional
    public NotificationTemplateResponse updateTemplateStatus(
            UUID templateId, UpdateNotificationTemplateStatusRequest request) {
        NotificationTemplate template = findTemplate(templateId);
        template.setStatus(request.status());
        template.setUpdatedAt(LocalDateTime.now());
        NotificationTemplate saved = notificationTemplateRepository.save(template);
        refreshCacheAfterCommit();
        return toResponse(saved);
    }

    @Override
    public int refreshTemplateCache() {
        notificationTemplateCacheService.refreshEmailTemplates();
        return notificationTemplateRepository.findByNotificationType(EMAIL).size();
    }

    private NotificationTemplate findTemplate(UUID templateId) {
        return notificationTemplateRepository.findById(templateId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Notification template not found: " + templateId));
    }

    private String normalizeSupportedDecision(String decision) {
        String normalized = decision.trim().toUpperCase(Locale.ROOT);
        if (!"REVIEW".equals(normalized) && !"BLOCK".equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only REVIEW and BLOCK email templates are supported");
        }
        return normalized;
    }

    private String templateCode(String decision) {
        return "FRAUD_" + decision + "_EMAIL";
    }

    private void refreshCacheAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            notificationTemplateCacheService.refreshEmailTemplates();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notificationTemplateCacheService.refreshEmailTemplates();
            }
        });
    }

    private NotificationTemplateResponse toResponse(NotificationTemplate template) {
        return new NotificationTemplateResponse(
                template.getId(), template.getTemplateCode(), template.getNotificationType(),
                template.getFraudDecision(), template.getSubjectTemplate(), template.getBodyTemplate(),
                template.getStatus(), template.getCreatedBy(), template.getCreatedDate(), template.getUpdatedAt()
        );
    }
}
