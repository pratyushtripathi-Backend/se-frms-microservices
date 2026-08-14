package com.se.frms.transaction.service;

import com.se.frms.transaction.client.FraudEngineClient;
import com.se.frms.transaction.dto.FraudEvaluationRequest;
import com.se.frms.transaction.exception.FraudEngineException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class DuplicateFraudNotificationService {

    private final FraudEngineClient fraudEngineClient;

    @Async
    public void notifyFraudEngine(FraudEvaluationRequest request) {
        log.info("Async duplicate fraud notification started for transactionId={}", request.transactionId());
        try {
            fraudEngineClient.evaluate(request);
            log.info("Async duplicate fraud notification completed for transactionId={}", request.transactionId());
        } catch (FraudEngineException ex) {
            log.warn(
                    "Async duplicate fraud notification failed for transactionId={}: {}",
                    request.transactionId(),
                    ex.getMessage()
            );
            // Duplicate fraud is already decided and saved; this call is best-effort notification.
        }
    }
}
