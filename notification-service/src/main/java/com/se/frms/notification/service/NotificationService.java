package com.se.frms.notification.service;
import com.se.frms.notification.dto.FraudEvent;
import com.se.frms.notification.dto.NotificationResponse;
import com.se.frms.notification.dto.UpdateAlertStatusRequest;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface NotificationService {
    void handleFraudEvent(FraudEvent event);

    Page<NotificationResponse> getNotifications(
            UUID transactionId,
            String notificationType,
            String fraudDecision,
            String notificationStatus,
            String alertStatus,
            String recipient,
            Pageable pageable
    );

    // Same filters, but page/size nullable: size == null -> returns EVERYTHING (no pagination).
    Page<NotificationResponse> getNotifications(
            UUID transactionId,
            String notificationType,
            String fraudDecision,
            String notificationStatus,
            String alertStatus,
            String recipient,
            Integer page,
            Integer size
    );

    NotificationResponse getNotificationById(UUID notificationId);

    Page<NotificationResponse> getNotificationsByTransactionId(UUID transactionId, Pageable pageable);

    NotificationResponse updateAlertStatus(UUID notificationId, UpdateAlertStatusRequest request);

}
