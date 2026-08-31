package com.se.frms.notification.repository;

import  com.se.frms.notification.entity.NotificationTemplate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, UUID> {
    List<NotificationTemplate> findByNotificationType(String notificationType);

    List<NotificationTemplate> findByNotificationTypeAndStatusTrue(String notificationType);

    Optional<NotificationTemplate> findByTemplateCodeAndStatusTrue(String templateCode);

    boolean existsByTemplateCode(String templateCode);
}
