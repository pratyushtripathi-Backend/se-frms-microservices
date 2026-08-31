package com.se.frms.notification.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateNotificationTemplateStatusRequest(@NotNull Boolean status) {
}
