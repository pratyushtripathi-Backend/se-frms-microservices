package com.se.frms.rulecache.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "se_frms_decision_policy_cache",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_decision_policy_cache_policy",
                        columnNames = "policy_id"
                )
        }
)
public class DecisionPolicyCache {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "policy_id", nullable = false)
    private Integer policyId;

    @Column(nullable = false, length = 255)
    private String description;

    @Column(name = "allow_min_score", nullable = false)
    private Integer allowMinScore;

    @Column(name = "allow_max_score", nullable = false)
    private Integer allowMaxScore;

    @Column(name = "review_min_score", nullable = false)
    private Integer reviewMinScore;

    @Column(name = "review_max_score", nullable = false)
    private Integer reviewMaxScore;

    @Column(name = "block_min_score", nullable = false)
    private Integer blockMinScore;

    @Column(name = "block_max_score", nullable = false)
    private Integer blockMaxScore;

    @Builder.Default
    @Column(nullable = false)
    private Boolean status = true;

    @Column(name = "created_by", nullable = false)
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
