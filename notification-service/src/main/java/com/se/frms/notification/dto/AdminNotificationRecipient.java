package com.se.frms.notification.dto;

public record AdminNotificationRecipient(
        Integer userId,
        String name,
        String email,
        String phoneNumber
) {
}
