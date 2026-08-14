package com.se.frms.rulecache.controller;

import com.se.frms.rulecache.dto.ActiveRuleResponse;
import com.se.frms.rulecache.service.RuleCacheService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
public class RuleCacheController {

    private final RuleCacheService ruleCacheService;

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("rule-cache-service is running");
    }

    @GetMapping("/active")
    public ResponseEntity<List<ActiveRuleResponse>> getActiveRules() {
        return ResponseEntity.ok(ruleCacheService.getActiveRules());
    }

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> sync() {

        ruleCacheService.syncFromMonolith();

        return ResponseEntity.ok(
                Map.of(
                        "status",
                        true,
                        "message",
                        "Rule cache sync completed successfully"
                )
        );
    }
}