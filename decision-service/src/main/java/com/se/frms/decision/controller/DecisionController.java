package com.se.frms.decision.controller;

import com.se.frms.decision.dto.DecisionRequest;
import com.se.frms.decision.dto.DecisionResponse;
import com.se.frms.decision.service.DecisionService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/decisions")
@RequiredArgsConstructor
@Slf4j
public class DecisionController {
    private final DecisionService decisionService;

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("decision-service is running");
    }

    @GetMapping
    public ResponseEntity<Page<DecisionResponse>> getAll(Pageable pageable) {
        log.info("GET /api/v1/decisions received page={}, size={}", pageable.getPageNumber(), pageable.getPageSize());
        return ResponseEntity.ok(decisionService.getAll(pageable));
    }

    @GetMapping("/{decisionId}")
    public ResponseEntity<DecisionResponse> getById(@PathVariable UUID decisionId) {
        log.info("GET /api/v1/decisions/{} received", decisionId);
        return ResponseEntity.ok(decisionService.getById(decisionId));
    }

    @GetMapping("/transaction/{transactionId}")
    public ResponseEntity<DecisionResponse> getByTransactionId(@PathVariable UUID transactionId) {
        log.info("GET /api/v1/decisions/transaction/{} received", transactionId);
        return ResponseEntity.ok(decisionService.getByTransactionId(transactionId));
    }

    @PostMapping
    public ResponseEntity<DecisionResponse> process(@Valid @RequestBody DecisionRequest request) {
        log.info(
                "POST /api/v1/decisions received transactionId={}, scoringId={}, totalRiskScore={}",
                request.transactionId(),
                request.scoringId(),
                request.totalRiskScore()
        );
        return ResponseEntity.ok(decisionService.process(request));
    }
}
