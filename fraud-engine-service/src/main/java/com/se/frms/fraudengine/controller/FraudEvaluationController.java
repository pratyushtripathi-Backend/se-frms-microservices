package com.se.frms.fraudengine.controller;

import com.se.frms.fraudengine.dto.FraudEvaluationRequest;
import com.se.frms.fraudengine.dto.FraudEvaluationResponse;
import com.se.frms.fraudengine.service.FraudEvaluationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fraud-evaluations")
@RequiredArgsConstructor
@Slf4j
public class FraudEvaluationController {

    private final FraudEvaluationService fraudEvaluationService;

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("fraud-engine-service is running");
    }

    @PostMapping
    public ResponseEntity<FraudEvaluationResponse> evaluate(@Valid @RequestBody FraudEvaluationRequest request) {
        log.info("POST /api/v1/fraud-evaluations received transactionId={}", request.transactionId());
        return ResponseEntity.ok(fraudEvaluationService.evaluate(request));
    }
}
