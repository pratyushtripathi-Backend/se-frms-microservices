package com.se.frms.notification.controller;

import com.se.frms.notification.dto.CreateNotificationTemplateRequest;
import com.se.frms.notification.dto.NotificationTemplateResponse;
import com.se.frms.notification.dto.UpdateNotificationTemplateRequest;
import com.se.frms.notification.dto.UpdateNotificationTemplateStatusRequest;
import com.se.frms.notification.service.NotificationTemplateManagementService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Management endpoints for REVIEW and BLOCK email templates. */
@RestController
@RequestMapping("/api/v1/notification-templates")
@RequiredArgsConstructor
public class NotificationTemplateController {
    private static final String INTERNAL_API_KEY_HEADER = "X-INTERNAL-API-KEY";

    private final NotificationTemplateManagementService notificationTemplateManagementService;

    @org.springframework.beans.factory.annotation.Value("${notification.monolith.internal-api-key}")
    private String internalApiKey;

    @GetMapping
    public ResponseEntity<?> getTemplates(
            @RequestHeader(name = INTERNAL_API_KEY_HEADER, required = false) String providedApiKey) {
        if (!isAuthorized(providedApiKey)) {
            return forbidden();
        }
        return ResponseEntity.ok(notificationTemplateManagementService.getTemplates());
    }

    @GetMapping("/{templateId}")
    public ResponseEntity<?> getTemplateById(@PathVariable UUID templateId,
            @RequestHeader(name = INTERNAL_API_KEY_HEADER, required = false) String providedApiKey) {
        if (!isAuthorized(providedApiKey)) {
            return forbidden();
        }
        return ResponseEntity.ok(notificationTemplateManagementService.getTemplateById(templateId));
    }

    @PostMapping
    public ResponseEntity<?> createTemplate(@Valid @RequestBody CreateNotificationTemplateRequest request,
            @RequestHeader(name = INTERNAL_API_KEY_HEADER, required = false) String providedApiKey) {
        if (!isAuthorized(providedApiKey)) {
            return forbidden();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(notificationTemplateManagementService.createTemplate(request));
    }

    @PatchMapping("/{templateId}")
    public ResponseEntity<?> updateTemplate(
            @PathVariable UUID templateId,
            @Valid @RequestBody UpdateNotificationTemplateRequest request,
            @RequestHeader(name = INTERNAL_API_KEY_HEADER, required = false) String providedApiKey) {
        if (!isAuthorized(providedApiKey)) {
            return forbidden();
        }
        return ResponseEntity.ok(notificationTemplateManagementService.updateTemplate(templateId, request));
    }

    @PatchMapping("/{templateId}/status")
    public ResponseEntity<?> updateTemplateStatus(
            @PathVariable UUID templateId,
            @Valid @RequestBody UpdateNotificationTemplateStatusRequest request,
            @RequestHeader(name = INTERNAL_API_KEY_HEADER, required = false) String providedApiKey) {
        if (!isAuthorized(providedApiKey)) {
            return forbidden();
        }
        return ResponseEntity.ok(notificationTemplateManagementService.updateTemplateStatus(templateId, request));
    }

    @PostMapping("/refresh-cache")
    public ResponseEntity<?> refreshCache(
            @RequestHeader(name = INTERNAL_API_KEY_HEADER, required = false) String providedApiKey) {
        if (!isAuthorized(providedApiKey)) {
            return forbidden();
        }
        int templateCount = notificationTemplateManagementService.refreshTemplateCache();
        return ResponseEntity.ok(Map.of(
                "message", "Notification template cache refreshed successfully",
                "templateCount", templateCount
        ));
    }

    private boolean isAuthorized(String providedApiKey) {
        return providedApiKey != null && internalApiKey != null && MessageDigest.isEqual(
                internalApiKey.getBytes(StandardCharsets.UTF_8),
                providedApiKey.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Invalid internal API key"));
    }
}
