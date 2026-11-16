package com.se.frms.ai.repository;

import com.se.frms.ai.entity.AiAuditLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AiAuditLogRepository extends JpaRepository<AiAuditLog, UUID> {
}
