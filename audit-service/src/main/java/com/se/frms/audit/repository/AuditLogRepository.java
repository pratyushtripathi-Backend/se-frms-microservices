package com.se.frms.audit.repository;

import com.se.frms.audit.entity.AuditLog;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
    Page<AuditLog> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId, Pageable pageable);
}
