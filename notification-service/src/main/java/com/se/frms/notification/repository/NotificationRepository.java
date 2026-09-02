package com.se.frms.notification.repository;

import com.se.frms.notification.entity.Notification;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface NotificationRepository extends JpaRepository<Notification, UUID>, JpaSpecificationExecutor<Notification> {
    boolean existsByTransactionIdAndNotificationTypeAndFraudDecisionAndRecipient(
            UUID transactionId, String notificationType, String fraudDecision, String recipient
    );

    List<Notification> findTop100ByNotificationStatusAndRetryCountLessThanOrderByUpdatedAtAsc(
            String notificationStatus, Integer retryCount
    );

    @Modifying
    @Transactional
    @Query("UPDATE Notification n SET n.notificationStatus = 'PENDING', "
            + "n.retryCount = n.retryCount + 1, n.updatedAt = :now "
            + "WHERE n.id = :id AND n.notificationStatus = 'FAILED' AND n.retryCount < :maxAttempts")
    int claimForRetry(@Param("id") UUID id, @Param("maxAttempts") int maxAttempts, @Param("now") LocalDateTime now);
}
