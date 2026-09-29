package com.se.frms.notification.controller;

import com.se.frms.notification.dto.NotificationListResponse;
import com.se.frms.notification.dto.NotificationResponse;
import com.se.frms.notification.service.NotificationService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<PageResponse<NotificationListResponse>> getNotifications(
            @RequestParam(required = false) UUID transactionId,
            @RequestParam(required = false) String notificationType,
            @RequestParam(required = false) String fraudDecision,
            @RequestParam(required = false) String notificationStatus,
            @RequestParam(required = false) String recipient,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        return ResponseEntity.ok(PageResponse.of(notificationService.getNotificationsList(
                transactionId, notificationType, fraudDecision, notificationStatus, recipient, page, size
        )));
    }

    @GetMapping("/dashboard/feed")
    public ResponseEntity<PageResponse<NotificationResponse>> getDashboardAlertFeed(
            @RequestParam(required = false) String fraudDecision,
            @PageableDefault(size = 10, sort = "createdDate", direction = org.springframework.data.domain.Sort.Direction.DESC)
            Pageable pageable
    ) {
        return ResponseEntity.ok(PageResponse.of(notificationService.getNotifications(
                null, "DASHBOARD", fraudDecision, null, null, pageable
        )));
    }

    @GetMapping("/{notificationId}")
    public ResponseEntity<NotificationResponse> getNotificationById(@PathVariable UUID notificationId) {
        return ResponseEntity.ok(notificationService.getNotificationById(notificationId));
    }

    @GetMapping("/transaction/{transactionId}")
    public ResponseEntity<PageResponse<NotificationResponse>> getNotificationsByTransactionId(
            @PathVariable UUID transactionId,
            @PageableDefault(size = 20, sort = "createdDate", direction = org.springframework.data.domain.Sort.Direction.DESC)
            Pageable pageable
    ) {
        return ResponseEntity.ok(PageResponse.of(notificationService.getNotificationsByTransactionId(transactionId, pageable)));
    }

    /** Compact page wrapper: page is 0-based; with no size, everything is returned in one page. */
    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public static <T> PageResponse<T> of(Page<T> result) {
            return result.getPageable().isPaged()
                    ? new PageResponse<>(result.getContent(), result.getNumber(), result.getSize(),
                            result.getTotalElements(), result.getTotalPages())
                    : new PageResponse<>(result.getContent(), 0, result.getNumberOfElements(),
                            result.getTotalElements(), 1);
        }
    }
}
