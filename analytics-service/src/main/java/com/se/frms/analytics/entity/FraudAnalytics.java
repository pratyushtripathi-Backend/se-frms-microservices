package com.se.frms.analytics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "se_frms_fraud_analytics")
public class FraudAnalytics {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "transaction_id", nullable = false, unique = true)
    private UUID transactionId;

    @Column(name = "scoring_id", nullable = false)
    private UUID scoringId;

    @Column(name = "decision_id", nullable = false)
    private UUID decisionId;

    @Column(name = "total_risk_score", nullable = false)
    private Integer totalRiskScore;

    @Column(name = "fraud_decision", nullable = false, length = 20)
    private String fraudDecision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "triggered_rules", columnDefinition = "jsonb")
    private Map<String, Object> triggeredRules;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "transaction_data", columnDefinition = "jsonb")
    private Map<String, Object> transactionData;

    @Column(nullable = false)
    private Boolean status;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = true;
        }
        if (createdBy == null || createdBy.isBlank()) {
            createdBy = "ANALYTICS_SERVICE";
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
