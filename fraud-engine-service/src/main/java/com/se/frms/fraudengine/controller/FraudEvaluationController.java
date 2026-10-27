package com.se.frms.fraudengine.controller;

import com.se.frms.fraudengine.dto.FraudEvaluationBatchItem;
import com.se.frms.fraudengine.dto.FraudEvaluationRequest;
import com.se.frms.fraudengine.dto.FraudEvaluationResponse;
import com.se.frms.fraudengine.service.FraudEvaluationService;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
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

    static final int MAX_BATCH_SIZE = 200;

    private final FraudEvaluationService fraudEvaluationService;
    private final Validator validator;

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("fraud-engine-service is running");
    }

    /**
     * Evaluates several transactions in one call (transaction-service sends up to ~20-50
     * at a time). Each one is evaluated exactly as by POST /api/v1/fraud-evaluations and
     * gets its own result in the same position: status 200 with the same response, or the
     * status and error text the single call would have answered for it.
     */
    @PostMapping("/batch")
    public ResponseEntity<List<FraudEvaluationBatchItem>> evaluateBatch(@RequestBody List<FraudEvaluationRequest> requests) {
        if (requests == null || requests.isEmpty() || requests.size() > MAX_BATCH_SIZE) {
            return ResponseEntity.badRequest().build();
        }
        log.debug("POST /api/v1/fraud-evaluations/batch received size={}", requests.size());
        FraudEvaluationBatchItem[] results = new FraudEvaluationBatchItem[requests.size()];
        List<FraudEvaluationRequest> valid = new ArrayList<>(requests.size());
        List<Integer> validIndexes = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            FraudEvaluationRequest request = requests.get(i);
            if (request == null || !validator.validate(request).isEmpty()) {
                // Same answer as the single endpoint's validation error.
                results[i] = new FraudEvaluationBatchItem(request != null ? request.transactionId() : null,
                        400, null, "Invalid fraud evaluation request");
            } else {
                valid.add(request);
                validIndexes.add(i);
            }
        }
        if (!valid.isEmpty()) {
            List<FraudEvaluationBatchItem> evaluated = fraudEvaluationService.evaluateBatch(valid);
            for (int k = 0; k < validIndexes.size(); k++) {
                results[validIndexes.get(k)] = evaluated.get(k);
            }
        }
        return ResponseEntity.ok(List.of(results));
    }

    @PostMapping
    public ResponseEntity<FraudEvaluationResponse> evaluate(@Valid @RequestBody FraudEvaluationRequest request) {
        log.debug("POST /api/v1/fraud-evaluations received transactionId={}", request.transactionId());
        return ResponseEntity.ok(fraudEvaluationService.evaluate(request));
    }
}
