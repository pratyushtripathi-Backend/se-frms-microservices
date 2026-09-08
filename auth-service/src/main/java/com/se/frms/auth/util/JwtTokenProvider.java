package com.se.frms.auth.util;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenProvider {

    @Value("${frms.security.jwt.secret}")
    private String secret;

    @Value("${frms.security.jwt.expiration-minutes:60}")
    private long expirationMinutes;

    public String generateToken(String username, String roles) {
        Date now = new Date();
        Date expiresAt = new Date(now.getTime() + expirationMinutes * 60_000);

        return Jwts.builder()
                .setSubject(username)
                .claim("roles", roles)
                .setIssuedAt(now)
                .setExpiration(expiresAt)
                .signWith(signingKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public long expirationSeconds() {
        return expirationMinutes * 60;
    }

    private Key signingKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
