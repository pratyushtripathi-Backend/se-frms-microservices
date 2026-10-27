package com.se.frms.transaction.service;

import com.se.frms.transaction.client.FraudEngineClient;
import com.se.frms.transaction.constant.TransactionStatus;
import com.se.frms.transaction.dto.FraudEvaluationRequest;
import com.se.frms.transaction.dto.FraudEvaluationResponse;
import com.se.frms.transaction.exception.FraudEngineException;
import com.se.frms.transaction.repository.TransactionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import com.se.frms.transaction.exception.FraudEngineBatchNotSupportedException;
import com.se.frms.transaction.filter.CorrelationIdFilter;
import org.slf4j.MDC;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Calls fraud-engine for one saved transaction and stores the result.
 *
 * Called by TransactionIngestConsumer (on evaluationExecutor) - no longer an @Async
 * method on the HTTP request path. The result is stored with one UPDATE instead of
 * findById + save.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionEvaluationService {

    private final TransactionRepository transactionRepository;
    private final FraudEngineClient fraudEngineClient;
    // Writes the result with batched UPDATEs (same columns and values as before).
    private final TransactionResultWriter resultWriter;

    public void evaluateNow(UUID transactionId, Map<String, Object> transactionData) {
        evaluateNow(transactionId, transactionData, null);
    }

    public void evaluateNow(UUID transactionId, Map<String, Object> transactionData, Long ingressEpochMs) {
        long startedAt = System.nanoTime();
        long startedEpochMs = System.currentTimeMillis();
        log.debug("Fraud evaluation started for transactionId={}", transactionId);
        try {
            FraudEvaluationResponse fraudResponse = fraudEngineClient.evaluate(
                    new FraudEvaluationRequest(transactionId, transactionData)
            );
            recordResult(transactionId, fraudResponse, ingressEpochMs, startedAt, startedEpochMs);
        } catch (FraudEngineException ex) {
            recordFailure(transactionId, ex);
        }
    }

    /** One transaction of a batch evaluation. */
    public record BatchEntry(UUID transactionId, Map<String, Object> transactionData, Long ingressEpochMs,
                             String correlationId) {
    }

    /**
     * Same as evaluateNow() for each entry, but with ONE call to fraud-engine for all of
     * them. Each transaction's result is stored exactly as evaluateNow() stores it
     * (status, remarks, FRAUD_ENGINE_FAILED on its own error) and logged with its own
     * correlation id.
     *
     * @throws FraudEngineBatchNotSupportedException fraud-engine has no batch endpoint
     *         (older version) - nothing was evaluated, the caller uses evaluateNow().
     */
    public void evaluateBatch(List<BatchEntry> entries) {
        long startedAt = System.nanoTime();
        long startedEpochMs = System.currentTimeMillis();
        List<FraudEvaluationRequest> requests = entries.stream()
                .map(e -> new FraudEvaluationRequest(e.transactionId(), e.transactionData(), e.correlationId()))
                .toList();
        List<FraudEngineClient.Outcome> outcomes = fraudEngineClient.evaluateBatch(requests);
        for (int i = 0; i < entries.size(); i++) {
            BatchEntry entry = entries.get(i);
            FraudEngineClient.Outcome outcome = outcomes.get(i);
            withCorrelationId(entry.correlationId(), () -> {
                if (outcome.error() != null) {
                    recordFailure(entry.transactionId(), outcome.error());
                } else {
                    recordResult(entry.transactionId(), outcome.response(), entry.ingressEpochMs(), startedAt, startedEpochMs);
                }
            });
        }
    }

    private void recordResult(UUID transactionId, FraudEvaluationResponse fraudResponse, Long ingressEpochMs,
                              long startedAt, long startedEpochMs) {
        String status = resolveDecision(fraudResponse);
        long evaluatedAt = System.nanoTime();
        resultWriter.write(
                transactionId, status, fraudResponse != null ? fraudResponse.decisionReason() : null,
                LocalDateTime.now());
        long finishedAt = System.nanoTime();
        long completedEpochMs = System.currentTimeMillis();
        log.debug("transaction_timing transactionId={} status={} ingressEpochMs={} completedEpochMs={} ingressToWorkerMs={} fraudCallMs={} resultUpdateMs={} serverCompletionMs={}",
                transactionId, status, ingressEpochMs, completedEpochMs,
                ingressEpochMs == null ? null : startedEpochMs - ingressEpochMs,
                (evaluatedAt - startedAt) / 1_000_000.0,
                (finishedAt - evaluatedAt) / 1_000_000.0,
                ingressEpochMs == null ? null : completedEpochMs - ingressEpochMs);
        // The one INFO line per transaction (detailed timing above is DEBUG; the
        // "performance" profile turns it on).
        log.info(
                "Fraud evaluation completed for transactionId={}, status={}, riskScore={}",
                transactionId,
                status,
                fraudResponse != null ? fraudResponse.totalRiskScore() : null
        );
    }

    private void recordFailure(UUID transactionId, FraudEngineException ex) {
        resultWriter.write(
                transactionId, TransactionStatus.FRAUD_ENGINE_FAILED.name(), ex.getMessage(), LocalDateTime.now());
        log.error("Fraud evaluation failed for transactionId={}", transactionId, ex);
    }

    private static void withCorrelationId(String correlationId, Runnable work) {
        String previous = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        }
        try {
            work.run();
        } finally {
            if (previous != null) {
                MDC.put(CorrelationIdFilter.MDC_KEY, previous);
            } else {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            }
        }
    }

    private String resolveDecision(FraudEvaluationResponse response) {
        if (response == null || !StringUtils.hasText(response.finalDecision())) {
            return TransactionStatus.REVIEW.name();
        }
        return response.finalDecision();
    }
}
