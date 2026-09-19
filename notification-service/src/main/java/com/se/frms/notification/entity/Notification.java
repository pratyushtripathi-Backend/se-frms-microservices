package com.se.frms.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
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
    @Lob
    @Column(columnDefinition = "TEXT")
    private String message;
    private String fraudDecision;
    private Integer riskScore;
    private String notificationStatus;
    // Provider (MSG24x7) MessageId for SMS sends only - used to look up real
    // carrier delivery status later. Not exposed in any frontend response DTO.
    private String messageId;
    private String alertStatus;
    @Column(nullable = false, columnDefinition = "INTEGER DEFAULT 0")
    private Integer retryCount;
    private String failureReason;
    private Boolean status;
    private String createdBy;
    private LocalDateTime createdDate;
    private LocalDateTime updatedAt;
}
