package com.se.frms.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "se_frms_notification")
public class Notification {
    @Id
    @GeneratedValue
    private UUID id;

    private UUID transactionId;
    private String notificationType;
    private String recipient;
    private String subject;
    private String message;
    private String fraudDecision;
    private Integer riskScore;
    private String notificationStatus;
    private String failureReason;
    private Boolean status;
    private String createdBy;
    private LocalDateTime createdDate;
    private LocalDateTime updatedAt;
}
