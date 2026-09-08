package com.se.frms.auth.dto;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        String username,
        String roles
) {
}
