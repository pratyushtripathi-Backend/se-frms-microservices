package com.se.frms.gateway.controller;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Circuit-breaker fallback targets. When transaction-service (or
 * auth-service) is down or its circuit is open, Spring Cloud Gateway
 * forwards here instead of hanging or bubbling up a raw connection error,
 * so callers always get a fast, well-formed response.
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @GetMapping("/transactions")
    @PostMapping("/transactions")
    public ResponseEntity<Map<String, Object>> transactionServiceFallback() {
        return unavailable("transaction-service");
    }

    @GetMapping("/auth")
    @PostMapping("/auth")
    public ResponseEntity<Map<String, Object>> authServiceFallback() {
        return unavailable("auth-service");
    }

    @GetMapping("/fraud-evaluations")
    @PostMapping("/fraud-evaluations")
    public ResponseEntity<Map<String, Object>> fraudEngineServiceFallback() {
        return unavailable("fraud-engine-service");
    }

    @GetMapping("/scoring")
    @PostMapping("/scoring")
    public ResponseEntity<Map<String, Object>> scoringServiceFallback() {
        return unavailable("scoring-service");
    }

    @GetMapping("/decisions")
    @PostMapping("/decisions")
    @org.springframework.web.bind.annotation.PatchMapping("/decisions")
    public ResponseEntity<Map<String, Object>> decisionServiceFallback() {
        return unavailable("decision-service");
    }

    @GetMapping("/audit-logs")
    @PostMapping("/audit-logs")
    public ResponseEntity<Map<String, Object>> auditServiceFallback() {
        return unavailable("audit-service");
    }

    @GetMapping("/notifications")
    @PostMapping("/notifications")
    @org.springframework.web.bind.annotation.PatchMapping("/notifications")
    public ResponseEntity<Map<String, Object>> notificationServiceFallback() {
        return unavailable("notification-service");
    }

    @GetMapping("/analytics")
    @PostMapping("/analytics")
    public ResponseEntity<Map<String, Object>> analyticsServiceFallback() {
        return unavailable("analytics-service");
    }

    private ResponseEntity<Map<String, Object>> unavailable(String service) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "status", 503,
                "error", "Service Unavailable",
                "message", service + " is currently unavailable. Please retry shortly.",
                "timestamp", Instant.now().toString()
        ));
    }
}
