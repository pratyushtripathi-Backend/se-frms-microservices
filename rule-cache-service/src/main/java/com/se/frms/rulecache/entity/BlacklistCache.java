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
        name = "se_frms_blacklist_cache",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_blacklist_cache_blacklist",
                        columnNames = "blacklist_id"
                )
        }
)
public class BlacklistCache {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "blacklist_id", nullable = false)
    private Integer blacklistId;

    // IP / DEVICE / LOCATION
    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "value", nullable = false)
    private String value;

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
