package com.se.frms.decision.controller;

import com.se.frms.decision.dto.CaseResponse;
import com.se.frms.decision.dto.DecisionBatchItem;
import com.se.frms.decision.exception.ExternalServiceException;
import com.se.frms.decision.dto.DecisionRequest;
import com.se.frms.decision.dto.DecisionResponse;
import com.se.frms.decision.dto.DecisionReviewRequest;
import com.se.frms.decision.service.DecisionService;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/decisions")
@RequiredArgsConstructor
@Slf4j
public class DecisionController {

    static final int MAX_BATCH_SIZE = 500;
    private final DecisionService decisionService;
    private final Validator validator;

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("decision-service is running");
    }

    // No explicit sort was ever requested here, so findAll(pageable) fell
    // back to the database's natural row order (effectively oldest-first) -
    // the Decision Table page always showed the oldest decisions on top.
    // Defaulting the sort to createdAt DESC puts the latest decision first
    // whenever the caller doesn't ask for a different sort explicitly.
    @GetMapping
    public ResponseEntity<Page<DecisionResponse>> getAll(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        log.info(
                "GET /api/v1/decisions received page={}, size={}, year={}, startDate={}, endDate={}",
                pageable.getPageNumber(), pageable.getPageSize(), year, startDate, endDate
        );
        return ResponseEntity.ok(decisionService.getAll(year, startDate, endDate, pageable));
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

    /**
     * Decides several transactions in one call (Fraud Engine sends up to ~50 at a time
     * instead of one HTTP call each). Every transaction goes through exactly the same
     * validation and decisionService.process() as POST /api/v1/decisions and gets its own
     * result in the same position; one failing transaction never affects the others.
     */
    @PostMapping("/batch")
    public ResponseEntity<List<DecisionBatchItem>> processBatch(@RequestBody List<DecisionRequest> requests) {
        if (requests == null || requests.isEmpty() || requests.size() > MAX_BATCH_SIZE) {
            return ResponseEntity.badRequest().build();
        }
        List<DecisionBatchItem> results = new ArrayList<>(requests.size());
        for (DecisionRequest request : requests) {
            UUID transactionId = request != null ? request.transactionId() : null;
            // Same answers as the single endpoint and its GlobalExceptionHandler.
            if (request == null || !validator.validate(request).isEmpty()) {
                results.add(new DecisionBatchItem(transactionId, 400, null, "Invalid decision request"));
                continue;
            }
            try {
                results.add(new DecisionBatchItem(transactionId, 200, decisionService.process(request), null));
            } catch (ResponseStatusException ex) {
                // e.g. 503 "No active decision policy" - same status and text as the single call.
                results.add(new DecisionBatchItem(transactionId, ex.getStatusCode().value(), null, ex.getReason()));
            } catch (ExternalServiceException ex) {
                results.add(new DecisionBatchItem(transactionId, 502, null, ex.getMessage()));
            } catch (UnsupportedOperationException ex) {
                results.add(new DecisionBatchItem(transactionId, 501, null, ex.getMessage()));
            } catch (RuntimeException ex) {
                log.error("Decision failed in batch transactionId={}", transactionId, ex);
                results.add(new DecisionBatchItem(transactionId, 500, null, "Unable to process decision request"));
            }
        }
        return ResponseEntity.ok(results);
    }

    @PostMapping
    public ResponseEntity<DecisionResponse> process(@Valid @RequestBody DecisionRequest request) {
        log.debug(
                "POST /api/v1/decisions received transactionId={}, scoringId={}, totalRiskScore={}",
                request.transactionId(),
                request.scoringId(),
                request.totalRiskScore()
        );
        return ResponseEntity.ok(decisionService.process(request));
    }

    // GET /api/v1/decisions/cases?status=REVIEW - case-management list (defaults to REVIEW)
    @GetMapping("/cases")
    public ResponseEntity<Page<CaseResponse>> getCases(
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        log.info("GET /api/v1/decisions/cases received status={}, page={}, size={}",
                status, pageable.getPageNumber(), pageable.getPageSize());
        return ResponseEntity.ok(decisionService.getCases(status, pageable));
    }

    // PATCH /api/v1/decisions/{decisionId}/review - admin Allow/Block action
    @PatchMapping("/{decisionId}/review")
    public ResponseEntity<DecisionResponse> reviewDecision(
            @PathVariable UUID decisionId,
            @Valid @RequestBody DecisionReviewRequest request
    ) {
        log.info("PATCH /api/v1/decisions/{}/review received finalDecision={}",
                decisionId, request.finalDecision());
        return ResponseEntity.ok(decisionService.reviewDecision(decisionId, request));
    }
}
