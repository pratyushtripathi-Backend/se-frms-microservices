package com.se.frms.decision.service;

import com.se.frms.decision.entity.Decision;
import com.se.frms.decision.repository.DecisionRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DecisionPersistenceService {

    private static final String SYSTEM_USER = "DECISION_SERVICE";

    private final DecisionRepository decisionRepository;

    @Async
    @Transactional
    public void saveDecision(
            UUID decisionId,
            UUID transactionId,
            UUID scoringId,
            Integer totalRiskScore,
            String finalDecision,
            String reason,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        long startedAt = System.nanoTime();
        try {
            Decision decision = decisionRepository.findByTransactionId(transactionId)
                    .orElseGet(Decision::new);
            if (decision.getId() == null) {
                decision.setId(decisionId);
            }
            decision.setTransactionId(transactionId);
            decision.setScoringId(scoringId);
            decision.setTotalRiskScore(totalRiskScore);
            decision.setFinalDecision(finalDecision);
            decision.setDecisionReason(reason);
            decision.setStatus(true);
            decision.setCreatedBy(SYSTEM_USER);
            if (decision.getCreatedAt() == null) {
                decision.setCreatedAt(createdAt);
            }
            decision.setUpdatedAt(updatedAt);
            decisionRepository.save(decision);
            log.info(
                    "Decision persisted asynchronously transactionId={}, decisionId={}, elapsedMs={}",
                    transactionId,
                    decision.getId(),
                    elapsedMillis(startedAt)
            );
        } catch (RuntimeException ex) {
            log.error(
                    "Async decision persistence failed transactionId={}, decisionId={}",
                    transactionId,
                    decisionId,
                    ex
            );
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
