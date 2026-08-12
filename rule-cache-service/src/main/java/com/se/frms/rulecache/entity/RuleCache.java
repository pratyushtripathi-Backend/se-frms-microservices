package com.se.frms.rulecache.entity;

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
@Table(name = "se_frms_rule_cache")
public class RuleCache {
    @Id
    @GeneratedValue
    private UUID id;

    private Integer ruleId;
    private Integer categoryId;
    private String ruleCode;
    private String ruleName;
    private String ruleDescription;
    private String categoryName;
    private Integer ruleScore;
    private Boolean status;
    private String createdBy;
    private LocalDateTime createdDate;
    private LocalDateTime updatedAt;
}
