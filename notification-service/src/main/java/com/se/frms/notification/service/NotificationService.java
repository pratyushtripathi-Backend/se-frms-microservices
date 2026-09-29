package com.se.frms.notification.service;

import com.se.frms.notification.dto.FraudEvent;
import com.se.frms.notification.dto.NotificationListResponse;
import com.se.frms.notification.dto.NotificationResponse;
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
            String recipient,
            Pageable pageable
    );

    Page<NotificationResponse> getNotifications(
            UUID transactionId,
            String notificationType,
            String fraudDecision,
            String notificationStatus,
            String recipient,
            Integer page,
            Integer size
    );

    Page<NotificationListResponse> getNotificationsList(
            UUID transactionId,
            String notificationType,
            String fraudDecision,
            String notificationStatus,
            String recipient,
            Pageable pageable
    );

    Page<NotificationListResponse> getNotificationsList(
            UUID transactionId,
            String notificationType,
            String fraudDecision,
            String notificationStatus,
            String recipient,
            Integer page,
            Integer size
    );

    NotificationResponse getNotificationById(UUID notificationId);

    Page<NotificationResponse> getNotificationsByTransactionId(UUID transactionId, Pageable pageable);

}
