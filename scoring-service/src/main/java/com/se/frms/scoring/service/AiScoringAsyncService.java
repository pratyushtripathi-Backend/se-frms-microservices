package com.se.frms.scoring.service;

import com.se.frms.scoring.client.AiScoringClient;
import com.se.frms.scoring.dto.AiRiskScoreResponse;
import com.se.frms.scoring.repository.ScoringRepository;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI "shadow" risk score - runs in parallel with, but never blocking, the rule-based scoring
 * response (ScoringServiceImpl.process() already returned before this even starts). Evaluation
 * data only: fills in Scoring.aiRiskScore/aiReasoning after the fact so a combined
 * (rule + AI) score can be compared against real outcomes later - decision-service never reads
 * these fields and the live ALLOW/REVIEW/BLOCK decision is completely unaffected.
 *
 * Off by default (frms.ai.risk-score.enabled=false): this calls an external LLM per transaction,
 * which has a real cost and a real rate limit, unlike the rest of this service.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiScoringAsyncService {

    // The batched writer (ScoringPersistenceService) may not have persisted this scoringId yet
    // by the time this runs - but an LLM call alone (seconds) vastly outlasts that writer's own
    // window (sub-second even under load), so one short wait-and-retry is enough; this is not
    // the same retry budget as ScoringPersistenceService's own save-retry, which is for genuine
    // DB failures, not this ordering race.
    private static final int MAX_UPDATE_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 500L;

    private final AiScoringClient aiScoringClient;
    private final ScoringRepository scoringRepository;

    @Value("${frms.ai.risk-score.enabled:false}")
    private boolean aiRiskScoreEnabled;

    @Async("aiScoringExecutor")
    public void scoreAsync(UUID scoringId, UUID transactionId, Map<String, Object> transactionData) {
        if (!aiRiskScoreEnabled) {
            return;
        }
        try {
            AiRiskScoreResponse response = aiScoringClient.scoreRisk(transactionData);
            if (response == null || response.score() == null) {
                log.warn("AI risk-score returned no score transactionId={}, scoringId={}", transactionId, scoringId);
                return;
            }
            persistWithRetry(scoringId, transactionId, response);
        } catch (RuntimeException ex) {
            log.warn("AI risk-score call failed transactionId={}, scoringId={}: {}", transactionId, scoringId, ex.getMessage());
        }
    }

    private void persistWithRetry(UUID scoringId, UUID transactionId, AiRiskScoreResponse response) {
        for (int attempt = 1; attempt <= MAX_UPDATE_ATTEMPTS; attempt++) {
            int updated = updateRow(scoringId, response);
            if (updated > 0) {
                log.debug("AI risk-score stored transactionId={}, scoringId={}, score={}", transactionId, scoringId, response.score());
                return;
            }
            if (attempt < MAX_UPDATE_ATTEMPTS) {
                sleepQuietly(RETRY_DELAY_MS);
            }
        }
        log.warn(
                "AI risk-score computed but Scoring row not found after {} attempts transactionId={}, scoringId={}",
                MAX_UPDATE_ATTEMPTS, transactionId, scoringId
        );
    }

    @Transactional
    public int updateRow(UUID scoringId, AiRiskScoreResponse response) {
        return scoringRepository.updateAiRiskScore(scoringId, response.score(), response.reasoning());
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
