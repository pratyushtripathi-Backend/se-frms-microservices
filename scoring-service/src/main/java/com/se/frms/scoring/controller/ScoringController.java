package com.se.frms.scoring.controller;

import com.se.frms.scoring.dto.ScoringRequest;
import com.se.frms.scoring.dto.ScoringResponse;
import com.se.frms.scoring.service.ScoringService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/scoring")
@RequiredArgsConstructor
@Slf4j
public class ScoringController {
    private final ScoringService scoringService;

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("scoring-service is running");
    }

    @PostMapping
    public ResponseEntity<ScoringResponse> process(@Valid @RequestBody ScoringRequest request) {
        log.info("POST /api/v1/scoring received transactionId={}", request.transactionId());
        return ResponseEntity.ok(scoringService.process(request));
    }

    // GET /api/v1/scoring/{scoringId}
    @GetMapping("/{scoringId}")
    public ResponseEntity<ScoringResponse> getByScoringId(@PathVariable UUID scoringId) {
        log.info("GET /api/v1/scoring/{}", scoringId);
        return ResponseEntity.ok(scoringService.getByScoringId(scoringId));
    }

    // GET /api/v1/scoring/transaction/{transactionId}  -> latest scoring attempt
    @GetMapping("/transaction/{transactionId}")
    public ResponseEntity<ScoringResponse> getLatestByTransactionId(@PathVariable UUID transactionId) {
        log.info("GET /api/v1/scoring/transaction/{}", transactionId);
        return ResponseEntity.ok(scoringService.getLatestByTransactionId(transactionId));
    }

    // GET /api/v1/scoring/transaction/{transactionId}/history -> all attempts
    @GetMapping("/transaction/{transactionId}/history")
    public ResponseEntity<List<ScoringResponse>> getHistoryByTransactionId(@PathVariable UUID transactionId) {
        log.info("GET /api/v1/scoring/transaction/{}/history", transactionId);
        return ResponseEntity.ok(scoringService.getHistoryByTransactionId(transactionId));
    }
}