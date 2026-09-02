package com.se.frms.notification.controller;

import com.se.frms.notification.dto.NotificationResponse;
import com.se.frms.notification.dto.UpdateAlertStatusRequest;
import com.se.frms.notification.service.NotificationService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<Page<NotificationResponse>> getNotifications(
            @RequestParam(required = false) UUID transactionId,
            @RequestParam(required = false) String notificationType,
            @RequestParam(required = false) String fraudDecision,
            @RequestParam(required = false) String notificationStatus,
            @RequestParam(required = false) String alertStatus,
            @RequestParam(required = false) String recipient,
            @PageableDefault(size = 20, sort = "createdDate", direction = org.springframework.data.domain.Sort.Direction.DESC)
            Pageable pageable
    ) {
        return ResponseEntity.ok(notificationService.getNotifications(
                transactionId, notificationType, fraudDecision, notificationStatus, alertStatus, recipient, pageable
        ));
    }

    @GetMapping("/dashboard/feed")
    public ResponseEntity<Page<NotificationResponse>> getDashboardAlertFeed(
            @RequestParam(required = false) String fraudDecision,
            @RequestParam(required = false) String alertStatus,
            @PageableDefault(size = 10, sort = "createdDate", direction = org.springframework.data.domain.Sort.Direction.DESC)
            Pageable pageable
    ) {
        return ResponseEntity.ok(notificationService.getNotifications(
                null, "DASHBOARD", fraudDecision, "SENT", alertStatus, null, pageable
        ));
    }

    @GetMapping("/{notificationId}")
    public ResponseEntity<NotificationResponse> getNotificationById(@PathVariable UUID notificationId) {
        return ResponseEntity.ok(notificationService.getNotificationById(notificationId));
    }

    @PatchMapping("/{notificationId}/alert-status")
    public ResponseEntity<NotificationResponse> updateAlertStatus(
            @PathVariable UUID notificationId,
            @jakarta.validation.Valid @RequestBody UpdateAlertStatusRequest request
    ) {
        return ResponseEntity.ok(notificationService.updateAlertStatus(notificationId, request));
    }

    @GetMapping("/transaction/{transactionId}")
    public ResponseEntity<Page<NotificationResponse>> getNotificationsByTransactionId(
            @PathVariable UUID transactionId,
            @PageableDefault(size = 20, sort = "createdDate", direction = org.springframework.data.domain.Sort.Direction.DESC)
            Pageable pageable
    ) {
        return ResponseEntity.ok(notificationService.getNotificationsByTransactionId(transactionId, pageable));
    }

}
