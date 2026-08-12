package com.se.frms.decision.controller;

import com.se.frms.decision.dto.DecisionRequest;
import com.se.frms.decision.dto.DecisionResponse;
import com.se.frms.decision.service.DecisionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/decisions")
@RequiredArgsConstructor
public class DecisionController {
    private final DecisionService decisionService;

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("decision-service is running");
    }

    @PostMapping
    public ResponseEntity<DecisionResponse> process(@Valid @RequestBody DecisionRequest request) {
        return ResponseEntity.ok(decisionService.process(request));
    }
}
