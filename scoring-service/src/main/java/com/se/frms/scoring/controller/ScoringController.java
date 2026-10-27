package com.se.frms.scoring.controller;

import com.se.frms.scoring.dto.MatchedRuleHistoryResponse;
import com.se.frms.scoring.dto.ScoringBatchItem;
import com.se.frms.scoring.dto.ScoringHistoryResponse;
import com.se.frms.scoring.dto.ScoringRequest;
import com.se.frms.scoring.dto.ScoringResponse;
import com.se.frms.scoring.exception.RulesVersionUnknownException;
import com.se.frms.scoring.service.RulesVersionCache;
import com.se.frms.scoring.service.ScoringService;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.Optional;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RequestParam;
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
    static final String RULES_VERSION_HEADER = "X-Frms-Rules-Version";

    private final ScoringService scoringService;
    private final RulesVersionCache rulesVersionCache;
    private final Validator validator;

    static final int MAX_BATCH_SIZE = 500;

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("scoring-service is running");
    }

    @PostMapping
    public ResponseEntity<ScoringResponse> process(@Valid @RequestBody ScoringRequest request) {
        log.debug("POST /api/v1/scoring received transactionId={}", request.transactionId());
        // A compact request (rules sent earlier, see RulesVersionCache) becomes the same
        // full request as before; unknown version -> 409, Fraud Engine resends in full.
        ScoringRequest fullRequest = rulesVersionCache.resolve(request)
                .orElseThrow(() -> new RulesVersionUnknownException(request.rulesVersion()));
        ScoringResponse response = scoringService.process(fullRequest);
        if (rulesVersionCache.has(request.rulesVersion())) {
            return ResponseEntity.ok().header(RULES_VERSION_HEADER, request.rulesVersion()).body(response);
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Scores several transactions in one call (Fraud Engine sends up to ~50 at a time
     * instead of one HTTP call each). Every transaction goes through exactly the same
     * steps as POST /api/v1/scoring - validation, rules-version lookup, scoringService
     * .process() - and gets its own result in the same position; one failing transaction
     * never affects the others.
     */
    @PostMapping("/batch")
    public ResponseEntity<List<ScoringBatchItem>> processBatch(@RequestBody List<ScoringRequest> requests) {
        if (requests == null || requests.isEmpty() || requests.size() > MAX_BATCH_SIZE) {
            return ResponseEntity.badRequest().build();
        }
        List<ScoringBatchItem> results = new ArrayList<>(requests.size());
        String storedVersion = null;
        for (ScoringRequest request : requests) {
            results.add(processOne(request));
            if (request != null && rulesVersionCache.has(request.rulesVersion())) {
                storedVersion = request.rulesVersion();
            }
        }
        if (storedVersion != null) {
            return ResponseEntity.ok().header(RULES_VERSION_HEADER, storedVersion).body(results);
        }
        return ResponseEntity.ok(results);
    }

    private ScoringBatchItem processOne(ScoringRequest request) {
        UUID transactionId = request != null ? request.transactionId() : null;
        // Same answers as the single endpoint and its GlobalExceptionHandler.
        if (request == null || !validator.validate(request).isEmpty()) {
            return new ScoringBatchItem(transactionId, 400, null, "Invalid scoring request");
        }
        Optional<ScoringRequest> fullRequest = rulesVersionCache.resolve(request);
        if (fullRequest.isEmpty()) {
            return new ScoringBatchItem(transactionId, 409, null,
                    new RulesVersionUnknownException(request.rulesVersion()).getMessage());
        }
        try {
            return new ScoringBatchItem(transactionId, 200, scoringService.process(fullRequest.get()), null);
        } catch (RuntimeException ex) {
            log.error("Scoring failed in batch transactionId={}", transactionId, ex);
            return new ScoringBatchItem(transactionId, 500, null, "Unable to process scoring request");
        }
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

    @GetMapping("/matched-rules")
    public ResponseEntity<Page<MatchedRuleHistoryResponse>> getAllMatchedRules(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @PageableDefault(sort = "createdDate", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        log.info(
                "GET /api/v1/scoring/matched-rules received page={}, size={}, year={}, startDate={}, endDate={}",
                pageable.getPageNumber(), pageable.getPageSize(), year, startDate, endDate
        );
        return ResponseEntity.ok(scoringService.getAllMatchedRules(year, startDate, endDate, pageable));
    }

    @GetMapping("/history")
    public ResponseEntity<Page<ScoringHistoryResponse>> getAllScorings(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @PageableDefault(sort = "createdDate", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        log.info(
                "GET /api/v1/scoring/history received page={}, size={}, year={}, startDate={}, endDate={}",
                pageable.getPageNumber(), pageable.getPageSize(), year, startDate, endDate
        );
        return ResponseEntity.ok(scoringService.getAllScorings(year, startDate, endDate, pageable));
    }
}
