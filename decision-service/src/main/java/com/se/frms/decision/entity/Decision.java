package com.se.frms.decision.entity;

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
@Table(name = "se_frms_decision")
public class Decision {
    @Id
    @GeneratedValue
    private UUID id;

    private UUID transactionId;
    private UUID scoringId;
    private Integer totalRiskScore;
    private String finalDecision;
    private String decisionReason;
    private Boolean status;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
