package com.se.frms.decision.service.impl;

import com.se.frms.decision.cache.DecisionPolicyCache;
import com.se.frms.decision.dto.DecisionRequest;
import com.se.frms.decision.dto.DecisionPolicyResponse;
import com.se.frms.decision.dto.DecisionResponse;
import com.se.frms.decision.entity.Decision;
import com.se.frms.decision.repository.DecisionRepository;
import com.se.frms.decision.service.DecisionPersistenceService;
import com.se.frms.decision.service.DecisionService;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    @Value("${decision.threshold.allow-max:39}")
    private Integer allowMaxScore;

    @Value("${decision.threshold.review-max:69}")
    private Integer reviewMaxScore;

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
    public Page<DecisionResponse> getAll(Pageable pageable) {
        log.info("Fetching decisions page={}, size={}", pageable.getPageNumber(), pageable.getPageSize());
        return decisionRepository.findAll(pageable).map(this::mapToResponse);
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

    private String resolveDecision(Integer totalRiskScore, DecisionPolicyResponse activePolicy) {
        if (activePolicy != null) {
            if (isBetween(totalRiskScore, activePolicy.allowMinScore(), activePolicy.allowMaxScore())) {
                return ALLOW;
            }
            if (isBetween(totalRiskScore, activePolicy.reviewMinScore(), activePolicy.reviewMaxScore())) {
                return REVIEW;
            }
            if (isBetween(totalRiskScore, activePolicy.blockMinScore(), activePolicy.blockMaxScore())) {
                return BLOCK;
            }
            return REVIEW;
        }

        if (totalRiskScore <= allowMaxScore) {
            return ALLOW;
        }
        if (totalRiskScore <= reviewMaxScore) {
            return REVIEW;
        }
        return BLOCK;
    }

    private String buildDecisionReason(
            Integer totalRiskScore,
            String finalDecision,
            DecisionPolicyResponse activePolicy
    ) {
        if (activePolicy != null) {
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

        return switch (finalDecision) {
            case ALLOW -> "Risk score " + totalRiskScore + " is within allow threshold 0-" + allowMaxScore;
            case REVIEW -> "Risk score " + totalRiskScore + " is within review threshold "
                    + (allowMaxScore + 1) + "-" + reviewMaxScore;
            case BLOCK -> "Risk score " + totalRiskScore + " is above review threshold " + reviewMaxScore;
            default -> "Decision calculated from configured thresholds";
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
