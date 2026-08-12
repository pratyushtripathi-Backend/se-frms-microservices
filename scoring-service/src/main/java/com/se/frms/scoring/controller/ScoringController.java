package com.se.frms.scoring.controller;

import com.se.frms.scoring.dto.ScoringRequest;
import com.se.frms.scoring.dto.ScoringResponse;
import com.se.frms.scoring.service.ScoringService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/scoring")
@RequiredArgsConstructor
public class ScoringController {
    private final ScoringService scoringService;

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("scoring-service is running");
    }

    @PostMapping
    public ResponseEntity<ScoringResponse> process(@Valid @RequestBody ScoringRequest request) {
        return ResponseEntity.ok(scoringService.process(request));
    }
}
