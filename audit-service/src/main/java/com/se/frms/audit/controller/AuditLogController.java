package com.se.frms.audit.controller;

import com.se.frms.audit.dto.AuditLogResponse;
import com.se.frms.audit.service.AuditService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-logs")
@RequiredArgsConstructor
@Slf4j
public class AuditLogController {

    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<Page<AuditLogResponse>> getAll(@PageableDefault(size = 20) Pageable pageable) {
        log.info("GET /api/v1/audit-logs received page={}, size={}", pageable.getPageNumber(), pageable.getPageSize());
        return ResponseEntity.ok(auditService.getAll(pageable));
    }

    @GetMapping("/{auditLogId}")
    public ResponseEntity<AuditLogResponse> getById(@PathVariable UUID auditLogId) {
        log.info("GET /api/v1/audit-logs/{} received", auditLogId);
        return ResponseEntity.ok(auditService.getById(auditLogId));
    }

    @GetMapping("/transaction/{transactionId}")
    public ResponseEntity<Page<AuditLogResponse>> getByTransactionId(
            @PathVariable UUID transactionId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        log.info(
                "GET /api/v1/audit-logs/transaction/{} received page={}, size={}",
                transactionId,
                pageable.getPageNumber(),
                pageable.getPageSize()
        );
        return ResponseEntity.ok(auditService.getByTransactionId(transactionId, pageable));
    }
}
