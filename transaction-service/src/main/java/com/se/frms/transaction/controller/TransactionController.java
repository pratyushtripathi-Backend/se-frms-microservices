package com.se.frms.transaction.controller;

import com.se.frms.transaction.dto.TransactionDetailsResponse;
import com.se.frms.transaction.dto.TransactionRequest;
import com.se.frms.transaction.dto.TransactionResponse;
import com.se.frms.transaction.service.TransactionService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Slf4j
public class TransactionController {

    private final TransactionService transactionService;

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("transaction-service is running");
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> process(@Valid @RequestBody TransactionRequest request) {
        log.info(
                "POST /api/v1/transactions received externalTransactionId={}, merchantId={}, userId={}, channel={}, transactionType={}, amount={}, currency={}",
                request.externalTransactionId(),
                request.merchantId(),
                request.userId(),
                request.channel(),
                request.transactionType(),
                request.amount(),
                request.currency()
        );
        return ResponseEntity.ok(transactionService.process(request));
    }

    @GetMapping
    public ResponseEntity<Page<TransactionDetailsResponse>> getAll(
            Pageable pageable,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String transactionType,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate
    ) {
        log.info(
                "GET /api/v1/transactions page={}, size={}, status={}, merchantId={}, userId={}, channel={}, transactionType={}, currency={}, fromDate={}, toDate={}",
                pageable.getPageNumber(),
                pageable.getPageSize(),
                status,
                merchantId,
                userId,
                channel,
                transactionType,
                currency,
                fromDate,
                toDate
        );
        return ResponseEntity.ok(transactionService.getAll(
                pageable,
                status,
                merchantId,
                userId,
                channel,
                transactionType,
                currency,
                fromDate,
                toDate
        ));
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionDetailsResponse> getById(@PathVariable UUID transactionId) {
        log.info("GET /api/v1/transactions/{} received", transactionId);
        return ResponseEntity.ok(transactionService.getById(transactionId));
    }
}
