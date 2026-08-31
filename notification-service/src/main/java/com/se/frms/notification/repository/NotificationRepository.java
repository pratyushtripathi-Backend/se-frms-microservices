package com.se.frms.notification.repository;

import com.se.frms.notification.entity.Notification;
import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface NotificationRepository extends JpaRepository<Notification, UUID>, JpaSpecificationExecutor<Notification> {
    boolean existsByTransactionIdAndNotificationTypeAndFraudDecisionAndRecipient(
            UUID transactionId, String notificationType, String fraudDecision, String recipient
    );

    List<Notification> findTop100ByNotificationStatusAndRetryCountLessThanOrderByUpdatedAtAsc(
            String notificationStatus, Integer retryCount
    );
}
