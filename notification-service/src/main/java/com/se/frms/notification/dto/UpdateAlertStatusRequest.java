package com.se.frms.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateAlertStatusRequest(
        @NotBlank
        @Pattern(regexp = "PENDING|UNDER_REVIEW|RESOLVED", flags = Pattern.Flag.CASE_INSENSITIVE,
                message = "alertStatus must be PENDING, UNDER_REVIEW, or RESOLVED")
        String alertStatus
) {
}
