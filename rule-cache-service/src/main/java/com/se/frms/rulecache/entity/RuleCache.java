package com.se.frms.rulecache.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "se_frms_rule_cache",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_rule_cache_rule",
                        columnNames = "rule_id"
                )
        }
)
public class RuleCache {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "rule_id", nullable = false)
    private Integer ruleId;

    @Column(name = "category_id", nullable = false)
    private Integer categoryId;

    @Column(name = "rule_code", nullable = false)
    private String ruleCode;

    @Column(name = "rule_name", nullable = false)
    private String ruleName;

    @Column(name = "rule_description", nullable = false, columnDefinition = "TEXT")
    private String ruleDescription;

    @Column(name = "category_name", nullable = false)
    private String categoryName;

    @Column(name = "rule_score", nullable = false)
    private Integer ruleScore;

    @Builder.Default
    @Column(nullable = false)
    private Boolean status = true;

    @Column(name = "created_by", nullable = false)
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

        updatedAt = now;

        if (status == null) {
            status = true;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}