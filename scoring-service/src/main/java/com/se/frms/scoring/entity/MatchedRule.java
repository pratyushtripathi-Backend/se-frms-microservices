package com.se.frms.scoring.entity;

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
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "se_frms_matched_rule")
public class MatchedRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scoring_id", nullable = false, unique = true)
    private Scoring scoring;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_code", columnDefinition = "jsonb", nullable = false)
    private List<String> ruleCodes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_name", columnDefinition = "jsonb", nullable = false)
    private List<String> ruleNames;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_expression", columnDefinition = "jsonb", nullable = false)
    private List<String> ruleExpressions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_score", columnDefinition = "jsonb", nullable = false)
    private List<Integer> ruleScores;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "calculated_score", columnDefinition = "jsonb", nullable = false)
    private List<Integer> calculatedScores;

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
        if (createdDate == null) createdDate = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = true;
        if (createdBy == null || createdBy.isBlank()) createdBy = "SCORING_SERVICE";
        if (ruleCodes == null) ruleCodes = List.of();
        if (ruleNames == null) ruleNames = List.of();
        if (ruleExpressions == null) ruleExpressions = List.of();
        if (ruleScores == null) ruleScores = List.of();
        if (calculatedScores == null) calculatedScores = List.of();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
