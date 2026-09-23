package com.se.frms.decision.service.impl;

import com.se.frms.decision.cache.DecisionPolicyCache;
import com.se.frms.decision.client.ScoringLookupClient;
import com.se.frms.decision.client.TransactionLookupClient;
import com.se.frms.decision.dto.CaseResponse;
import com.se.frms.decision.dto.DecisionRequest;
import com.se.frms.decision.dto.DecisionPolicyResponse;
import com.se.frms.decision.dto.DecisionResponse;
import com.se.frms.decision.dto.DecisionReviewedEvent;
import com.se.frms.decision.dto.DecisionReviewRequest;
import com.se.frms.decision.dto.ScoringLookupResponse;
import com.se.frms.decision.dto.TransactionLookupResponse;
import com.se.frms.decision.entity.Decision;
import com.se.frms.decision.exception.ExternalServiceException;
import com.se.frms.decision.producer.DecisionReviewedEventProducer;
import com.se.frms.decision.repository.DecisionRepository;
import com.se.frms.decision.service.DecisionPersistenceService;
import com.se.frms.decision.service.DecisionService;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;

import java.time.Instant;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
public class DecisionServiceImpl implements DecisionService {

    private static final String ALLOW = "ALLOW";
    private static final String REVIEW = "REVIEW";
    private static final String BLOCK = "BLOCK";

    private final DecisionRepository decisionRepository;

    private final DecisionPolicyCache decisionPolicyCache;
    private final DecisionPersistenceService decisionPersistenceService;
    private final ScoringLookupClient scoringLookupClient;
    private final TransactionLookupClient transactionLookupClient;
    private final DecisionReviewedEventProducer decisionReviewedEventProducer;

    @Override
    public DecisionResponse process(DecisionRequest request) {
        long startedAt = System.nanoTime();
        DecisionPolicyResponse activePolicy =
                decisionPolicyCache.getActivePolicy();
        String finalDecision = resolveDecision(request.totalRiskScore(), activePolicy);
        String reason = buildDecisionReason(request.totalRiskScore(), finalDecision, activePolicy);
        UUID decisionId = UUID.randomUUID();
        LocalDateTime decisionTimestamp = LocalDateTime.now();

        decisionPersistenceService.saveDecision(
                decisionId,
                request.transactionId(),
                request.scoringId(),
                request.totalRiskScore(),
                finalDecision,
                reason,
                decisionTimestamp,
                decisionTimestamp
        );

        log.info(
                "Decision calculated transactionId={}, scoringId={}, totalRiskScore={}, finalDecision={}, elapsedMs={}",
                request.transactionId(),
                request.scoringId(),
                request.totalRiskScore(),
                finalDecision,
                elapsedMillis(startedAt)
        );

        return new DecisionResponse(
                decisionId,
                request.transactionId(),
                request.scoringId(),
                request.totalRiskScore(),
                finalDecision,
                reason,
                decisionTimestamp,
                decisionTimestamp
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DecisionResponse> getAll(Integer year, LocalDate startDate, LocalDate endDate, Pageable pageable) {
        log.info(
                "Fetching decisions page={}, size={}, year={}, startDate={}, endDate={}",
                pageable.getPageNumber(), pageable.getPageSize(), year, startDate, endDate
        );

        return decisionRepository.findAll(buildCreatedAtFilter(year, startDate, endDate), pageable)
                .map(this::mapToResponse);
    }

    private Specification<Decision> buildCreatedAtFilter(Integer year, LocalDate startDate, LocalDate endDate) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (year != null) {
                predicates.add(criteriaBuilder.between(
                        root.get("createdAt"),
                        LocalDateTime.of(year, 1, 1, 0, 0, 0),
                        LocalDateTime.of(year, 12, 31, 23, 59, 59)
                ));
            }
            if (startDate != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("createdAt"), startDate.atStartOfDay()));
            }
            if (endDate != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("createdAt"), endDate.atTime(23, 59, 59)));
            }
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }

    @Override
    @Transactional(readOnly = true)
    public DecisionResponse getById(UUID decisionId) {
        log.info("Fetching decision by decisionId={}", decisionId);
        return decisionRepository.findById(decisionId)
                .map(this::mapToResponse)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Decision not found: " + decisionId
                ));
    }

    @Override
    @Transactional(readOnly = true)
    public DecisionResponse getByTransactionId(UUID transactionId) {
        log.info("Fetching decision by transactionId={}", transactionId);
        return decisionRepository.findByTransactionId(transactionId)
                .map(this::mapToResponse)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Decision not found for transactionId: " + transactionId
                ));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CaseResponse> getCases(String status, Pageable pageable) {
        String filterStatus = (status == null || status.isBlank()) ? REVIEW : status.trim().toUpperCase();
        log.info("Fetching case-management list status={}, page={}, size={}",
                filterStatus, pageable.getPageNumber(), pageable.getPageSize());
        return decisionRepository.findByFinalDecision(filterStatus, pageable).map(this::mapToCaseResponse);
    }

    @Override
    @Transactional
    public DecisionResponse reviewDecision(UUID decisionId, DecisionReviewRequest request) {
        String requested = request.finalDecision() == null ? null : request.finalDecision().trim().toUpperCase();
        if (!ALLOW.equals(requested) && !BLOCK.equals(requested)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "finalDecision must be ALLOW or BLOCK");
        }

        Decision decision = decisionRepository.findById(decisionId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Decision not found: " + decisionId
                ));

        String previousDecision = decision.getFinalDecision();
        decision.setFinalDecision(requested);
        if (request.remarks() != null && !request.remarks().isBlank()) {
            decision.setDecisionReason(request.remarks());
        }
        Decision saved = decisionRepository.save(decision);

        log.info(
                "Decision manually reviewed decisionId={}, transactionId={}, previousDecision={}, newDecision={}",
                saved.getId(),
                saved.getTransactionId(),
                previousDecision,
                saved.getFinalDecision()
        );

        // Best-effort: analytics-service's own copy of this decision only
        // ever reflects whatever fraud-engine-service originally published.
        // Without this, a case resolved here keeps counting toward the
        // dashboard's "Active Case" number forever. A skipped publish (e.g.
        // Kafka briefly unavailable) never fails this request - the review
        // itself is already saved above regardless.
        decisionReviewedEventProducer.publish(new DecisionReviewedEvent(
                saved.getTransactionId(),
                saved.getId(),
                previousDecision,
                saved.getFinalDecision(),
                Instant.now()
        ));

        return mapToResponse(saved);
    }

    private CaseResponse mapToCaseResponse(Decision decision) {
        // Best-effort enrichment: if scoring-service or transaction-service is
        // unreachable, the row still comes back with whatever decision-service
        // already has locally rather than failing the whole list.
        List<ScoringLookupResponse.MatchedRuleInfo> matchedRules = List.of();
        try {
            ScoringLookupResponse scoring = scoringLookupClient.getByScoringId(decision.getScoringId());
            if (scoring != null && scoring.matchedRules() != null) {
                matchedRules = scoring.matchedRules();
            }
        } catch (ExternalServiceException ex) {
            log.warn("Matched-rule detail unavailable for decisionId={}, scoringId={}",
                    decision.getId(), decision.getScoringId());
        }

        java.math.BigDecimal amount = null;
        String mode = null;
        try {
            TransactionLookupResponse transaction =
                    transactionLookupClient.getByTransactionId(decision.getTransactionId());
            if (transaction != null) {
                amount = transaction.amount();
                mode = transaction.channel();
            }
        } catch (ExternalServiceException ex) {
            log.warn("Transaction detail unavailable for decisionId={}, transactionId={}",
                    decision.getId(), decision.getTransactionId());
        }

        return new CaseResponse(
                decision.getId(),
                decision.getTransactionId(),
                amount,
                mode,
                decision.getTotalRiskScore(),
                matchedRules,
                decision.getFinalDecision(),
                decision.getDecisionReason(),
                decision.getCreatedAt(),
                decision.getUpdatedAt()
        );
    }

    private String resolveDecision(Integer totalRiskScore, DecisionPolicyResponse activePolicy) {
        // No hardcoded score bands here on purpose: a fraud decision must
        // only ever be made against an admin-configured Decision Policy.
        // If the policy cache hasn't been populated yet (fresh startup, or
        // the monolith -> rule-cache-service -> decision-service sync
        // hasn't completed), fail loudly instead of silently guessing
        // Allow/Review/Block from made-up thresholds.
        if (activePolicy == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "No active decision policy is configured. Cannot calculate a fraud decision without an admin-configured policy."
            );
        }

        if (isBetween(totalRiskScore, activePolicy.allowMinScore(), activePolicy.allowMaxScore())) {
            return ALLOW;
        }
        if (isBetween(totalRiskScore, activePolicy.reviewMinScore(), activePolicy.reviewMaxScore())) {
            return REVIEW;
        }
        if (isBetween(totalRiskScore, activePolicy.blockMinScore(), activePolicy.blockMaxScore())) {
            return BLOCK;
        }
        // Score falls in a gap between the admin-configured ranges (not a
        // hardcoded threshold) - send to manual review rather than guess.
        return REVIEW;
    }

    private String buildDecisionReason(
            Integer totalRiskScore,
            String finalDecision,
            DecisionPolicyResponse activePolicy
    ) {
        // resolveDecision() always throws before reaching here when
        // activePolicy is null, so this only ever runs with a real,
        // admin-configured policy - no hardcoded-threshold branch needed.
        return switch (finalDecision) {
            case ALLOW -> "Risk score " + totalRiskScore + " is within admin policy allow threshold "
                    + activePolicy.allowMinScore() + "-" + activePolicy.allowMaxScore();
            case REVIEW -> "Risk score " + totalRiskScore + " is within admin policy review threshold "
                    + activePolicy.reviewMinScore() + "-" + activePolicy.reviewMaxScore();
            case BLOCK -> "Risk score " + totalRiskScore + " is within admin policy block threshold "
                    + activePolicy.blockMinScore() + "-" + activePolicy.blockMaxScore();
            default -> "Decision calculated from admin decision policy";
        };
    }

    private boolean isBetween(Integer value, Integer min, Integer max) {
        return value >= min && value <= max;
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private DecisionResponse mapToResponse(Decision decision) {
        return new DecisionResponse(
                decision.getId(),
                decision.getTransactionId(),
                decision.getScoringId(),
                decision.getTotalRiskScore(),
                decision.getFinalDecision(),
                decision.getDecisionReason(),
                decision.getCreatedAt(),
                decision.getUpdatedAt()
        );
    }
}
