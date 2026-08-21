package com.se.frms.scoring.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Getter
@Setter
@Entity
@Table(name = "se_frms_matched_rule")
@Check(constraints = "rule_score >= 0 AND calculated_score >= 0")
public class MatchedRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.PERSIST)
    @JoinColumn(name = "scoring_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Scoring scoring;

    @Column(name = "rule_id", nullable = false)
    private Integer ruleId;

    @Column(name = "rule_code", nullable = false)
    private String ruleCode;

    @Column(name = "rule_name", nullable = false)
    private String ruleName;

    @Column(name = "rule_expression", columnDefinition = "TEXT")
    private String ruleExpression;

    @Column(name = "rule_score", nullable = false)
    private Integer ruleScore;

    @Column(name = "calculated_score", nullable = false)
    private Integer calculatedScore;

    @Column(nullable = false)
    private Boolean status;

    @Column(name = "created_by", length = 100, nullable = false)
    private String createdBy;

    @Column(name = "created_date", nullable = false)
    private LocalDateTime createdDate;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdDate == null) {
            createdDate = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = true;
        }
        if (createdBy == null || createdBy.isBlank()) {
            createdBy = "SCORING_SERVICE";
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
