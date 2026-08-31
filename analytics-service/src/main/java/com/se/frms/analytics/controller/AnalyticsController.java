package com.se.frms.analytics.controller;

import com.se.frms.analytics.dto.AnalyticsSummaryResponse;
import com.se.frms.analytics.dto.DecisionCountResponse;
import com.se.frms.analytics.dto.FraudAnalyticsResponse;
import com.se.frms.analytics.dto.RulePerformanceResponse;
import com.se.frms.analytics.service.AnalyticsService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Slf4j
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("analytics-service is running");
    }

    @GetMapping("/fraud")
    public ResponseEntity<Page<FraudAnalyticsResponse>> getAll(
            @PageableDefault(size = 20) Pageable pageable,
            @RequestParam(required = false) String fraudDecision,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate
    ) {
        log.info(
                "GET /api/v1/analytics/fraud received page={}, size={}, fraudDecision={}, fromDate={}, toDate={}",
                pageable.getPageNumber(),
                pageable.getPageSize(),
                fraudDecision,
                fromDate,
                toDate
        );
        return ResponseEntity.ok(analyticsService.getAll(pageable, fraudDecision, fromDate, toDate));
    }

    @GetMapping("/fraud/{analyticsId}")
    public ResponseEntity<FraudAnalyticsResponse> getById(@PathVariable UUID analyticsId) {
        log.info("GET /api/v1/analytics/fraud/{} received", analyticsId);
        return ResponseEntity.ok(analyticsService.getById(analyticsId));
    }

    @GetMapping("/fraud/transaction/{transactionId}")
    public ResponseEntity<FraudAnalyticsResponse> getByTransactionId(@PathVariable UUID transactionId) {
        log.info("GET /api/v1/analytics/fraud/transaction/{} received", transactionId);
        return ResponseEntity.ok(analyticsService.getByTransactionId(transactionId));
    }

    @GetMapping("/summary")
    public ResponseEntity<AnalyticsSummaryResponse> getSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate
    ) {
        log.info("GET /api/v1/analytics/summary received fromDate={}, toDate={}", fromDate, toDate);
        return ResponseEntity.ok(analyticsService.getSummary(fromDate, toDate));
    }

    @GetMapping("/decision-counts")
    public ResponseEntity<List<DecisionCountResponse>> getDecisionCounts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate
    ) {
        log.info("GET /api/v1/analytics/decision-counts received fromDate={}, toDate={}", fromDate, toDate);
        return ResponseEntity.ok(analyticsService.getDecisionCounts(fromDate, toDate));
    }

    @GetMapping("/rule-performance")
    public ResponseEntity<List<RulePerformanceResponse>> getRulePerformance(
            @RequestParam(required = false, defaultValue = "10") int limit
    ) {
        log.info("GET /api/v1/analytics/rule-performance received limit={}", limit);
        return ResponseEntity.ok(analyticsService.getRulePerformance(limit));
    }
}
