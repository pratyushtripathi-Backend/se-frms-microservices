package com.se.frms.rulecache.dto;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlacklistCacheSyncResponseDTO {

    private Integer blacklistId;

    private String type;

    private String value;

    private Boolean status;

    private String createdBy;
}
