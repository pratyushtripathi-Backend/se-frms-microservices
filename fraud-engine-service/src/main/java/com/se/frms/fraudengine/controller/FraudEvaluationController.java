package com.se.frms.fraudengine.controller;
import com.se.frms.fraudengine.dto.FraudEvaluationRequest;
import com.se.frms.fraudengine.dto.FraudEvaluationResponse;
import com.se.frms.fraudengine.service.FraudEvaluationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/fraud-evaluations")
@RequiredArgsConstructor
public class FraudEvaluationController {
    private final FraudEvaluationService fraudEvaluationService;
    @GetMapping("/health")
    public ResponseEntity<String> health() { return ResponseEntity.ok("fraud-engine-service is running"); }
    @PostMapping
    public ResponseEntity<FraudEvaluationResponse> evaluate(@Valid @RequestBody FraudEvaluationRequest request) {
        return ResponseEntity.ok(fraudEvaluationService.evaluate(request));
    }
}
