package com.se.frms.fraudengine.service.impl;

import com.se.frms.fraudengine.cache.ActiveBlacklistCache;
import com.se.frms.fraudengine.cache.ActiveRuleCache;
import com.se.frms.fraudengine.cache.GeocodeCache;
import com.se.frms.fraudengine.client.DecisionClient;
import com.se.frms.fraudengine.client.ScoringClient;
import com.se.frms.fraudengine.dto.ActiveBlacklistResponse;
import com.se.frms.fraudengine.dto.ActiveRuleResponse;
import com.se.frms.fraudengine.dto.DecisionRequest;
import com.se.frms.fraudengine.dto.DecisionResponse;
import com.se.frms.fraudengine.dto.FraudEvent;
import com.se.frms.fraudengine.dto.FraudEvaluationRequest;
import com.se.frms.fraudengine.dto.FraudEvaluationResponse;
import com.se.frms.fraudengine.dto.GeocodeResult;
import com.se.frms.fraudengine.dto.ScoringRequest;
import com.se.frms.fraudengine.dto.ScoringResponse;
import com.se.frms.fraudengine.filter.CorrelationIdFilter;
import com.se.frms.fraudengine.producer.FraudEventProducer;
import com.se.frms.fraudengine.service.FraudEvaluationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class FraudEvaluationServiceImpl implements FraudEvaluationService {

    private static final String DUPLICATE_FRAUD = "DUPLICATE_FRAUD";
    private static final String REVIEW = "REVIEW";
    private static final String BLACKLIST_CATEGORY = "BLACKLIST";
    private static final String RESOLVED_LOCATION_FIELD = "resolvedLocation";

    // LOCATION blacklist entries are matched against a computed "resolvedLocation"
    // field (city + ", " + country), resolved from latitude/longitude via Geoapify
    // Geocoding - see buildEnrichedTransactionData(). IP/DEVICE match directly
    // against fields Transaction Service already puts into transactionData.
    private static final Map<String, String> BLACKLIST_TYPE_TO_FIELD = Map.of(
            "IP", "ipAddress",
            "DEVICE", "deviceId",
            "LOCATION", RESOLVED_LOCATION_FIELD
    );

    private final ActiveRuleCache activeRuleCache;
    private final ActiveBlacklistCache activeBlacklistCache;
    private final GeocodeCache geocodeCache;
    private final ScoringClient scoringClient;
    private final DecisionClient decisionClient;
    private final FraudEventProducer fraudEventProducer;

    @Value("${frms.blacklist.virtual-rule.score:100}")
    private Integer blacklistVirtualRuleScore;

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

        long rulesStartedAt = System.nanoTime();
        List<ActiveRuleResponse> activeRules = activeRuleCache.getActiveRules();
        List<ActiveRuleResponse> blacklistVirtualRules = buildBlacklistVirtualRules();
        List<ActiveRuleResponse> combinedRules = new ArrayList<>(activeRules.size() + blacklistVirtualRules.size());
        combinedRules.addAll(activeRules);
        combinedRules.addAll(blacklistVirtualRules);
        long rulesMs = elapsedMillis(rulesStartedAt);
        log.info(
                "Using active rule cache transactionId={}, ruleCount={}, blacklistVirtualRuleCount={}",
                request.transactionId(),
                activeRules.size(),
                blacklistVirtualRules.size()
        );

        long locationStartedAt = System.nanoTime();
        Map<String, Object> scoringTransactionData = buildEnrichedTransactionData(request.transactionData());
        long locationMs = elapsedMillis(locationStartedAt);

        long scoringStartedAt = System.nanoTime();
        ScoringResponse scoringResponse = scoringClient.score(new ScoringRequest(
                request.transactionId(),
                combinedRules,
                scoringTransactionData
        ));
        long scoringMs = elapsedMillis(scoringStartedAt);

        Integer totalRiskScore = scoringResponse != null ? scoringResponse.totalRiskScore() : 0;
        long decisionStartedAt = System.nanoTime();
        DecisionResponse decisionResponse = decisionClient.decide(new DecisionRequest(
                request.transactionId(),
                scoringResponse != null ? scoringResponse.scoringId() : null,
                totalRiskScore,
                request.transactionData()
        ));
        long decisionMs = elapsedMillis(decisionStartedAt);

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

        long eventStartedAt = System.nanoTime();
        publishFraudEvent(
                request,
                response,
                scoringResponse != null ? scoringResponse.scoringId() : null,
                decisionResponse != null ? decisionResponse.decisionId() : null,
                scoringResponse != null && scoringResponse.triggeredRules() != null
                        ? scoringResponse.triggeredRules()
                        : Map.of()
        );
        long eventTriggerMs = elapsedMillis(eventStartedAt);
        log.info(
                "Fraud evaluation completed transactionId={}, finalDecision={}, totalRiskScore={}, rulesMs={}, locationMs={}, scoringMs={}, decisionMs={}, eventTriggerMs={}, elapsedMs={}",
                request.transactionId(),
                finalDecision,
                totalRiskScore,
                rulesMs,
                locationMs,
                scoringMs,
                decisionMs,
                eventTriggerMs,
                elapsedMillis(startedAt)
        );
        return response;
    }

    /**
     * Copies transactionData and adds a "resolvedLocation" key ("city, country"),
     * resolved from latitude/longitude via GeocodeCache (Geoapify Geocoding, cached).
     * The original request's map is left untouched - only the copy sent to Scoring
     * Service for this evaluation carries the extra field. If latitude/longitude are
     * missing, or geocoding fails/returns nothing, the original map is returned as-is
     * and LOCATION blacklist entries simply will not match for this transaction -
     * this never blocks or fails the evaluation.
     */
    private Map<String, Object> buildEnrichedTransactionData(Map<String, Object> transactionData) {

        BigDecimal latitude = extractCoordinate(transactionData, "latitude");
        BigDecimal longitude = extractCoordinate(transactionData, "longitude");

        if (latitude == null || longitude == null) {
            return transactionData;
        }

        Optional<GeocodeResult> geocodeResult = geocodeCache.resolve(latitude, longitude);

        if (geocodeResult.isEmpty() || geocodeResult.get().resolvedLocation() == null) {
            return transactionData;
        }

        Map<String, Object> enriched = new HashMap<>(transactionData);
        enriched.put(RESOLVED_LOCATION_FIELD, geocodeResult.get().resolvedLocation());
        return enriched;
    }

    private BigDecimal extractCoordinate(Map<String, Object> transactionData, String key) {

        Object value = transactionData.get(key);

        if (value == null) {
            return null;
        }

        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Turns each active IP / DEVICE / LOCATION blacklist cache entry into a synthetic
     * "virtual rule" shaped exactly like a normal ActiveRuleResponse
     * (ruleExpression = "field == 'value'"). Scoring Service's existing RuleEvaluator
     * then matches it against transactionData the same way it matches any known
     * rule, and persists a hit into the existing MatchedRule table with zero changes
     * to Scoring Service. No direct blacklist lookup/call happens during evaluation -
     * this only reads from the already-refreshed in-memory cache.
     */
    private List<ActiveRuleResponse> buildBlacklistVirtualRules() {
        List<ActiveBlacklistResponse> activeBlacklist = activeBlacklistCache.getActiveBlacklist();
        List<ActiveRuleResponse> virtualRules = new ArrayList<>(activeBlacklist.size());

        for (ActiveBlacklistResponse entry : activeBlacklist) {
            String field = mapBlacklistTypeToField(entry.type());
            if (field == null || entry.value() == null || entry.value().isBlank()) {
                continue;
            }

            String type = entry.type().toUpperCase(Locale.ROOT);
            String ruleExpression = field + " == '" + entry.value() + "'";

            virtualRules.add(new ActiveRuleResponse(
                    -entry.blacklistId(),
                    null,
                    type + "_BLACKLIST_MATCH",
                    type + " Blacklist Match",
                    "Transaction " + field + " matches a blacklisted " + type.toLowerCase(Locale.ROOT) + " value",
                    ruleExpression,
                    BLACKLIST_CATEGORY,
                    blacklistVirtualRuleScore,
                    true
            ));
        }

        return virtualRules;
    }

    private String mapBlacklistTypeToField(String type) {
        if (type == null) {
            return null;
        }
        return BLACKLIST_TYPE_TO_FIELD.get(type.toUpperCase(Locale.ROOT));
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
                Instant.now(),
                MDC.get(CorrelationIdFilter.MDC_KEY)
        ));
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
