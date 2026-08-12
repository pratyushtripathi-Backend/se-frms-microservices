package com.se.frms.scoring.entity;

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
@Table(name = "se_frms_scoring")
public class Scoring {
    @Id
    @GeneratedValue
    private UUID id;

    private UUID transactionId;
    private Integer totalRiskScore;
    private Boolean status;
    private String createdBy;
    private LocalDateTime createdDate;
    private LocalDateTime updatedAt;
}
