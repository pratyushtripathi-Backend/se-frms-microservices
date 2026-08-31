package com.se.frms.notification.dto;

public record MonolithApiResponse<T>(
        Boolean status,
        Integer responseCode,
        String responseMessage,
        T responseData
) {
}
