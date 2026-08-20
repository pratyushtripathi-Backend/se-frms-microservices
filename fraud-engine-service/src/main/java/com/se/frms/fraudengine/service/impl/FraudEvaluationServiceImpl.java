package com.se.frms.fraudengine.service.impl;

import com.se.frms.fraudengine.client.DecisionClient;
import com.se.frms.fraudengine.cache.ActiveRuleCache;
import com.se.frms.fraudengine.client.ScoringClient;
import com.se.frms.fraudengine.dto.ActiveRuleResponse;
import com.se.frms.fraudengine.dto.DecisionRequest;
import com.se.frms.fraudengine.dto.DecisionResponse;
import com.se.frms.fraudengine.dto.FraudEvent;
import com.se.frms.fraudengine.dto.FraudEvaluationRequest;
import com.se.frms.fraudengine.dto.FraudEvaluationResponse;
import com.se.frms.fraudengine.dto.ScoringRequest;
import com.se.frms.fraudengine.dto.ScoringResponse;
import com.se.frms.fraudengine.producer.FraudEventProducer;
import com.se.frms.fraudengine.service.FraudEvaluationService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class FraudEvaluationServiceImpl implements FraudEvaluationService {

    private static final String DUPLICATE_FRAUD = "DUPLICATE_FRAUD";
    private static final String REVIEW = "REVIEW";

    private final ActiveRuleCache activeRuleCache;
    private final ScoringClient scoringClient;
    private final DecisionClient decisionClient;
    private final FraudEventProducer fraudEventProducer;

    @Override
    public FraudEvaluationResponse evaluate(FraudEvaluationRequest request) {
        long startedAt = System.nanoTime();
        log.info("Fraud evaluation started transactionId={}", request.transactionId());

        if (isDuplicateFraud(request.transactionData())) {
            FraudEvaluationResponse response = new FraudEvaluationResponse(
                    request.transactionId(),
                    DUPLICATE_FRAUD,
                    100,
                    "Duplicate idempotent transaction reported by Transaction Service"
            );
            publishFraudEvent(request, response, null, null, Map.of("duplicate", true));
            log.info("Fraud evaluation completed as duplicate transactionId={}, elapsedMs={}", request.transactionId(), elapsedMillis(startedAt));
            return response;
        }

        List<ActiveRuleResponse> activeRules = activeRuleCache.getActiveRules();
        log.info("Using active rule cache transactionId={}, ruleCount={}", request.transactionId(), activeRules.size());
        ScoringResponse scoringResponse = scoringClient.score(new ScoringRequest(
                request.transactionId(),
                activeRules,
                request.transactionData()
        ));

        Integer totalRiskScore = scoringResponse != null ? scoringResponse.totalRiskScore() : 0;
        DecisionResponse decisionResponse = decisionClient.decide(new DecisionRequest(
                request.transactionId(),
                scoringResponse != null ? scoringResponse.scoringId() : null,
                totalRiskScore,
                request.transactionData()
        ));

        String finalDecision = decisionResponse != null && decisionResponse.finalDecision() != null
                ? decisionResponse.finalDecision()
                : REVIEW;
        String decisionReason = decisionResponse != null && decisionResponse.reason() != null
                ? decisionResponse.reason()
                : "Decision Service did not return a reason";
        FraudEvaluationResponse response = new FraudEvaluationResponse(
                request.transactionId(),
                finalDecision,
                totalRiskScore,
                decisionReason
        );

        publishFraudEvent(
                request,
                response,
                scoringResponse != null ? scoringResponse.scoringId() : null,
                decisionResponse != null ? decisionResponse.decisionId() : null,
                scoringResponse != null && scoringResponse.triggeredRules() != null
                        ? scoringResponse.triggeredRules()
                        : Map.of()
        );
        log.info(
                "Fraud evaluation completed transactionId={}, finalDecision={}, totalRiskScore={}, elapsedMs={}",
                request.transactionId(),
                finalDecision,
                totalRiskScore,
                elapsedMillis(startedAt)
        );
        return response;
    }

    private boolean isDuplicateFraud(Map<String, Object> transactionData) {
        return Boolean.TRUE.equals(transactionData.get("duplicateTransaction"))
                || DUPLICATE_FRAUD.equals(transactionData.get("fraudSignal"))
                || "DUPLICATE_EXTERNAL_TRANSACTION_ID".equals(transactionData.get("fraudSignal"));
    }

    private void publishFraudEvent(
            FraudEvaluationRequest request,
            FraudEvaluationResponse response,
            java.util.UUID scoringId,
            java.util.UUID decisionId,
            Map<String, Object> triggeredRules
    ) {
        fraudEventProducer.publish(new FraudEvent(
                request.transactionId(),
                scoringId,
                decisionId,
                response.totalRiskScore(),
                response.finalDecision(),
                request.transactionData(),
                triggeredRules,
                Instant.now()
        ));
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
