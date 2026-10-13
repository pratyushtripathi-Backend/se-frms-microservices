package com.se.frms.transaction.service;

import com.se.frms.transaction.client.FraudEngineClient;
import com.se.frms.transaction.constant.TransactionStatus;
import com.se.frms.transaction.dto.FraudEvaluationRequest;
import com.se.frms.transaction.dto.FraudEvaluationResponse;
import com.se.frms.transaction.exception.FraudEngineException;
import com.se.frms.transaction.repository.TransactionRepository;
import java.time.LocalDateTime;
import java.util.Map;
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

    public void evaluateNow(UUID transactionId, Map<String, Object> transactionData) {
        log.debug("Fraud evaluation started for transactionId={}", transactionId);
        try {
            FraudEvaluationResponse fraudResponse = fraudEngineClient.evaluate(
                    new FraudEvaluationRequest(transactionId, transactionData)
            );
            String status = resolveDecision(fraudResponse);
            transactionRepository.updateEvaluationResult(
                    transactionId, status, fraudResponse != null ? fraudResponse.decisionReason() : null,
                    LocalDateTime.now());
            log.info(
                    "Fraud evaluation completed for transactionId={}, status={}, riskScore={}",
                    transactionId,
                    status,
                    fraudResponse != null ? fraudResponse.totalRiskScore() : null
            );
        } catch (FraudEngineException ex) {
            transactionRepository.updateEvaluationResult(
                    transactionId, TransactionStatus.FRAUD_ENGINE_FAILED.name(), ex.getMessage(), LocalDateTime.now());
            log.error("Fraud evaluation failed for transactionId={}", transactionId, ex);
        }
    }

    private String resolveDecision(FraudEvaluationResponse response) {
        if (response == null || !StringUtils.hasText(response.finalDecision())) {
            return TransactionStatus.REVIEW.name();
        }
        return response.finalDecision();
    }
}
