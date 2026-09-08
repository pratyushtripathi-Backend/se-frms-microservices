package com.se.frms.audit.service;
import com.se.frms.audit.dto.AuditLogResponse;
import com.se.frms.audit.dto.AuditTrailDetailResponse;
import com.se.frms.audit.dto.FraudEvent;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AuditService {
    void handleFraudEvent(FraudEvent event);

    Page<AuditLogResponse> getAll(Pageable pageable);

    AuditLogResponse getById(UUID auditLogId);

    Page<AuditLogResponse> getByTransactionId(UUID transactionId, Pageable pageable);

    AuditTrailDetailResponse getTransactionDetails(UUID transactionId);
}
