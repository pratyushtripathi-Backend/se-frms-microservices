package com.se.frms.audit.service.impl;
import com.se.frms.audit.dto.AuditLogResponse;
import com.se.frms.audit.dto.AuditTrailDetailResponse;
import com.se.frms.audit.dto.FraudEvent;
import com.se.frms.audit.entity.AuditLog;
import com.se.frms.audit.repository.AuditLogRepository;
import com.se.frms.audit.service.AuditService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditServiceImpl implements AuditService {

    private static final String AUDIT_SERVICE = "AUDIT_SERVICE";
    private static final String FRAUD_ENGINE_SERVICE = "FRAUD_ENGINE_SERVICE";
    private static final String FRAUD_EVALUATION_COMPLETED = "FRAUD_EVALUATION_COMPLETED";

    private final AuditLogRepository auditLogRepository;

    @Override
    @Transactional
    public void handleFraudEvent(FraudEvent event) {
        long startedAt = System.nanoTime();
        AuditLog auditLog = new AuditLog();
        auditLog.setTransactionId(event.transactionId());
        auditLog.setServiceName(FRAUD_ENGINE_SERVICE);
        auditLog.setEventType(FRAUD_EVALUATION_COMPLETED);
        auditLog.setReferenceId(resolveReferenceId(event));
        auditLog.setEventDetails(buildEventDetails(event));
        auditLog.setPerformedBy(FRAUD_ENGINE_SERVICE);
        auditLog.setStatus(true);
        auditLog.setCreatedBy(AUDIT_SERVICE);
        auditLogRepository.save(auditLog);
        log.info(
                "Audit log saved transactionId={}, eventType={}, elapsedMs={}",
                event.transactionId(),
                FRAUD_EVALUATION_COMPLETED,
                elapsedMillis(startedAt)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> getAll(Pageable pageable) {
        log.info("Fetching audit logs page={}, size={}", pageable.getPageNumber(), pageable.getPageSize());
        return auditLogRepository.findAll(pageable).map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public AuditLogResponse getById(UUID auditLogId) {
        log.info("Fetching audit log by auditLogId={}", auditLogId);
        return auditLogRepository.findById(auditLogId)
                .map(this::mapToResponse)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Audit log not found: " + auditLogId
                ));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> getByTransactionId(UUID transactionId, Pageable pageable) {
        log.info(
                "Fetching audit logs by transactionId={} page={}, size={}",
                transactionId,
                pageable.getPageNumber(),
                pageable.getPageSize()
        );
        return auditLogRepository.findByTransactionIdOrderByCreatedAtAsc(transactionId, pageable)
                .map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public AuditTrailDetailResponse getTransactionDetails(UUID transactionId) {
        log.info("Fetching audit trail details for transactionId={}", transactionId);
        AuditLog auditLog = auditLogRepository.findTopByTransactionIdOrderByCreatedAtDesc(transactionId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No audit trail found for transactionId: " + transactionId
                ));
        return mapToDetailResponse(auditLog);
    }

    private AuditTrailDetailResponse mapToDetailResponse(AuditLog auditLog) {
        Map<String, Object> details = auditLog.getEventDetails() != null
                ? auditLog.getEventDetails()
                : Map.of();

        AuditTrailDetailResponse.ScoringDetails scoring = new AuditTrailDetailResponse.ScoringDetails(
                asUuid(details.get("scoringId")),
                asInteger(details.get("totalRiskScore"))
        );

        AuditTrailDetailResponse.DecisionDetails decision = new AuditTrailDetailResponse.DecisionDetails(
                asUuid(details.get("decisionId")),
                asString(details.get("fraudDecision"))
        );

        return new AuditTrailDetailResponse(
                auditLog.getId(),
                auditLog.getTransactionId(),
                auditLog.getServiceName(),
                auditLog.getEventType(),
                auditLog.getReferenceId(),
                auditLog.getPerformedBy(),
                auditLog.getStatus(),
                auditLog.getCreatedAt(),
                auditLog.getUpdatedAt(),
                scoring,
                decision,
                asMap(details.get("triggeredRules")),
                asMap(details.get("transactionData")),
                details
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private UUID asUuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer intValue) {
            return intValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private UUID resolveReferenceId(FraudEvent event) {
        if (event.decisionId() != null) {
            return event.decisionId();
        }
        if (event.scoringId() != null) {
            return event.scoringId();
        }
        return event.transactionId();
    }

    private Map<String, Object> buildEventDetails(FraudEvent event) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("transactionId", event.transactionId());
        details.put("scoringId", event.scoringId());
        details.put("decisionId", event.decisionId());
        details.put("totalRiskScore", event.totalRiskScore());
        details.put("fraudDecision", event.fraudDecision());
        details.put("triggeredRules", event.triggeredRules());
        details.put("transactionData", event.transactionData());
        details.put("occurredAt", event.occurredAt());
        return details;
    }

    private AuditLogResponse mapToResponse(AuditLog auditLog) {
        return new AuditLogResponse(
                auditLog.getId(),
                auditLog.getTransactionId(),
                auditLog.getServiceName(),
                auditLog.getEventType(),
                auditLog.getReferenceId(),
                auditLog.getEventDetails(),
                auditLog.getPerformedBy(),
                auditLog.getStatus(),
                auditLog.getCreatedBy(),
                auditLog.getCreatedAt(),
                auditLog.getUpdatedAt()
        );
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
