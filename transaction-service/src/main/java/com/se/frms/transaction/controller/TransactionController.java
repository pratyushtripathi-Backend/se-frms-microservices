package com.se.frms.transaction.controller;
import com.se.frms.transaction.dto.TransactionRequest;
import com.se.frms.transaction.dto.TransactionResponse;
import com.se.frms.transaction.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {
    private final TransactionService transactionService;
    @GetMapping("/health")
    public ResponseEntity<String> health() { return ResponseEntity.ok("transaction-service is running"); }
    @PostMapping
    public ResponseEntity<TransactionResponse> process(@Valid @RequestBody TransactionRequest request) {
        return ResponseEntity.ok(transactionService.process(request));
    }
}
