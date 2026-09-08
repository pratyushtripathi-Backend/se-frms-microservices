package com.se.frms.auth.service.impl;

import com.se.frms.auth.dto.LoginRequest;
import com.se.frms.auth.dto.LoginResponse;
import com.se.frms.auth.exception.InvalidCredentialsException;
import com.se.frms.auth.service.AuthService;
import com.se.frms.auth.util.JwtTokenProvider;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * MVP credential store: an in-memory user map with BCrypt-hashed passwords.
 *
 * This is intentionally the smallest thing that lets the gateway's JWT flow
 * work end-to-end. Before this goes anywhere near production, replace
 * {@link #USERS} with a real user store (a Postgres-backed
 * UserRepository/entity, same pattern as the other services) and this
 * class becomes the place that calls it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    // username -> [bcrypt password hash, comma-separated roles]
    // Default demo credentials: admin/admin123 (ADMIN), analyst/analyst123 (ANALYST)
    private static final Map<String, String[]> USERS = Map.of(
            "admin", new String[]{"$2a$10$7EqJtq98hPqEX7fNZaFWoOa2wLTLuXSSseIYyPS6XwFwj9fkHqNGa", "ADMIN"},
            "analyst", new String[]{"$2a$10$1u1u4gQzY6xQY0Zc9V0dqOKqf5wq8m0v0mVoZzB.KcjPTz8fJm7Qm", "ANALYST"}
    );

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public LoginResponse login(LoginRequest request) {
        String[] userRecord = USERS.get(request.username());
        if (userRecord == null || !PASSWORD_ENCODER.matches(request.password(), userRecord[0])) {
            log.warn("Login failed username={}", request.username());
            throw new InvalidCredentialsException("Invalid username or password");
        }

        String roles = userRecord[1];
        String token = jwtTokenProvider.generateToken(request.username(), roles);
        log.info("Login succeeded username={}, roles={}", request.username(), roles);

        return new LoginResponse(
                token,
                "Bearer",
                jwtTokenProvider.expirationSeconds(),
                request.username(),
                roles
        );
    }
}
