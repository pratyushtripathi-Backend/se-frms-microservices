package com.se.frms.transaction.service;

import com.se.frms.transaction.client.FraudEngineClient;
import com.se.frms.transaction.constant.TransactionStatus;
import com.se.frms.transaction.dto.FraudEvaluationRequest;
import com.se.frms.transaction.dto.FraudEvaluationResponse;
import com.se.frms.transaction.entity.TransactionMaster;
import com.se.frms.transaction.exception.FraudEngineException;
import com.se.frms.transaction.repository.TransactionRepository;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.se.frms.transaction.filter.CorrelationIdFilter;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionEvaluationService {

    private final TransactionRepository transactionRepository;
    private final FraudEngineClient fraudEngineClient;

    @Async
    public void evaluate(UUID transactionId, Map<String, Object> transactionData, String correlationId) {
        // This runs on the @Async executor thread, a different thread than
        // the one CorrelationIdFilter set MDC on, so re-establish it here
        // from the value the caller captured before invoking us. This is
        // what lets CorrelationIdRequestInterceptor forward the same
        // correlation ID onward to fraud-engine-service.
        if (correlationId != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        }
        try {
            evaluateInternal(transactionId, transactionData);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private void evaluateInternal(UUID transactionId, Map<String, Object> transactionData) {
        log.info("Async fraud evaluation started for transactionId={}", transactionId);
        try {
            FraudEvaluationResponse fraudResponse = fraudEngineClient.evaluate(
                    new FraudEvaluationRequest(transactionId, transactionData)
            );

            transactionRepository.findById(transactionId).ifPresent(transaction -> {
                transaction.setStatus(resolveDecision(fraudResponse));
                transaction.setRemarks(fraudResponse.decisionReason());
                transaction.setUpdatedAt(LocalDateTime.now());
                transactionRepository.save(transaction);
                log.info(
                        "Async fraud evaluation completed for transactionId={}, status={}, riskScore={}",
                        transactionId,
                        transaction.getStatus(),
                        fraudResponse != null ? fraudResponse.totalRiskScore() : null
                );
            });
        } catch (FraudEngineException ex) {
            transactionRepository.findById(transactionId).ifPresent(transaction -> {
                transaction.setStatus(TransactionStatus.FRAUD_ENGINE_FAILED.name());
                transaction.setRemarks(ex.getMessage());
                transaction.setUpdatedAt(LocalDateTime.now());
                transactionRepository.save(transaction);
                log.error("Async fraud evaluation failed for transactionId={}", transactionId, ex);
            });
        }
    }

    private String resolveDecision(FraudEvaluationResponse response) {
        if (response == null || !StringUtils.hasText(response.finalDecision())) {
            return TransactionStatus.REVIEW.name();
        }
        return response.finalDecision();
    }
}
