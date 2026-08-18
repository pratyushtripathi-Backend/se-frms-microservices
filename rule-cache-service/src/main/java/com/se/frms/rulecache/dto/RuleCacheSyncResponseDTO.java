package com.se.frms.rulecache.dto;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleCacheSyncResponseDTO {

    private Integer ruleId;

    private Integer categoryId;

    private String ruleCode;

    private String ruleName;

    private String ruleDescription;

    private String ruleExpression;

    private String categoryName;

    private Integer ruleScore;

    private Boolean status;

    private String createdBy;
}
