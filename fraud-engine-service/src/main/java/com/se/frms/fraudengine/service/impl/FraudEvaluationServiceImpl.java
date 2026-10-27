package com.se.frms.fraudengine.service.impl;

import com.se.frms.fraudengine.cache.ActiveBlacklistCache;
import com.se.frms.fraudengine.cache.BlacklistIndex;
import com.se.frms.fraudengine.cache.ActiveRuleCache;
import com.se.frms.fraudengine.cache.GeocodeCache;
import com.se.frms.fraudengine.client.BatchOutcome;
import com.se.frms.fraudengine.client.DecisionClient;
import com.se.frms.fraudengine.config.FraudBatchExecutor;
import com.se.frms.fraudengine.dto.FraudEvaluationBatchItem;
import com.se.frms.fraudengine.exception.ExternalServiceException;
import java.util.concurrent.CompletableFuture;
import com.se.frms.fraudengine.client.ScoringClient;
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
    private final FraudBatchExecutor fraudBatchExecutor;

    @Value("${frms.blacklist.virtual-rule.score:100}")
    private Integer blacklistVirtualRuleScore;

    @Override
    public FraudEvaluationResponse evaluate(FraudEvaluationRequest request) {
        long startedAt = System.nanoTime();
        log.debug("Fraud evaluation started transactionId={}", request.transactionId());

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
        ActiveRuleCache.Snapshot ruleSnapshot = activeRuleCache.getSnapshot();
        List<ActiveRuleResponse> activeRules = ruleSnapshot.rules();
        long activeRulesMs = elapsedMillis(rulesStartedAt);

        long locationStartedAt = System.nanoTime();
        Map<String, Object> scoringTransactionData = buildEnrichedTransactionData(request.transactionData());
        long locationMs = elapsedMillis(locationStartedAt);

        // Blacklist matching needs the enriched data (resolvedLocation), so it runs
        // after location resolution. Only entries that actually match this
        // transaction become rules - the request to Scoring Service no longer grows
        // with the size of the blacklist.
        long blacklistStartedAt = System.nanoTime();
        List<ActiveRuleResponse> blacklistVirtualRules = buildMatchedBlacklistRules(scoringTransactionData);
        List<ActiveRuleResponse> combinedRules = new ArrayList<>(activeRules.size() + blacklistVirtualRules.size());
        combinedRules.addAll(activeRules);
        combinedRules.addAll(blacklistVirtualRules);
        long rulesMs = activeRulesMs + elapsedMillis(blacklistStartedAt);
        log.debug(
                "Using active rule cache transactionId={}, ruleCount={}, blacklistVirtualRuleCount={}",
                request.transactionId(),
                activeRules.size(),
                blacklistVirtualRules.size()
        );

        long scoringStartedAt = System.nanoTime();
        ScoringResponse scoringResponse = scoringClient.score(new ScoringRequest(
                request.transactionId(),
                combinedRules,
                scoringTransactionData,
                ruleSnapshot.version(),
                activeRules.size(),
                null
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
     * Evaluates several transactions together. Each transaction goes through exactly the
     * same steps as evaluate() - duplicate check, active rules, location, blacklist
     * matching, scoring, decision, fraud event, completion log line - with the same data;
     * only the calls to Scoring and Decision Service are made once for the whole batch
     * (POST .../batch) instead of once per transaction. Every transaction gets its own
     * result: a failure (e.g. Scoring Service error) affects only that transaction, with
     * the same status and message the single call would have answered.
     */
    @Override
    public List<FraudEvaluationBatchItem> evaluateBatch(List<FraudEvaluationRequest> requests) {
        int size = requests.size();
        FraudEvaluationBatchItem[] results = new FraudEvaluationBatchItem[size];
        PreparedEvaluation[] prepared = new PreparedEvaluation[size];
        String batchRequestId = MDC.get(CorrelationIdFilter.MDC_KEY);

        // 1. Same preparation as evaluate(), for all transactions in parallel (a location
        //    lookup of one transaction does not hold up the others). One rule snapshot for
        //    the whole batch, as a single call reads one snapshot.
        ActiveRuleCache.Snapshot ruleSnapshot = activeRuleCache.getSnapshot();
        List<CompletableFuture<Void>> preparation = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int index = i;
            FraudEvaluationRequest request = requests.get(i);
            preparation.add(CompletableFuture.runAsync(() -> withRequestId(request, batchRequestId, () -> {
                try {
                    if (isDuplicateFraud(request.transactionData())) {
                        // Duplicates never call Scoring / Decision: exactly the single path.
                        results[index] = success(request, evaluate(request));
                    } else {
                        prepared[index] = prepare(request, ruleSnapshot);
                    }
                } catch (RuntimeException ex) {
                    results[index] = failure(request, ex);
                }
            }), fraudBatchExecutor));
        }
        CompletableFuture.allOf(preparation.toArray(new CompletableFuture[0])).join();

        List<Integer> toScore = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (prepared[i] != null) {
                toScore.add(i);
            }
        }
        if (toScore.isEmpty()) {
            return List.of(results);
        }

        // 2. One call to Scoring Service for all of them.
        long scoringStartedAt = System.nanoTime();
        List<BatchOutcome<ScoringResponse>> scorings = scoringClient.scoreBatch(
                toScore.stream().map(i -> prepared[i].scoringRequest()).toList(), fraudBatchExecutor);
        long scoringMs = elapsedMillis(scoringStartedAt);

        // 3. One call to Decision Service for those that were scored.
        List<Integer> toDecide = new ArrayList<>();
        List<DecisionRequest> decisionRequests = new ArrayList<>();
        for (int k = 0; k < toScore.size(); k++) {
            int i = toScore.get(k);
            BatchOutcome<ScoringResponse> scoring = scorings.get(k);
            if (scoring.error() != null) {
                // Same as evaluate(): scoring failed -> no decision, no event.
                results[i] = failure(requests.get(i), scoring.error());
                continue;
            }
            prepared[i] = prepared[i].withScoring(scoring.value());
            ScoringResponse scoringResponse = scoring.value();
            toDecide.add(i);
            decisionRequests.add(new DecisionRequest(
                    requests.get(i).transactionId(),
                    scoringResponse != null ? scoringResponse.scoringId() : null,
                    scoringResponse != null ? scoringResponse.totalRiskScore() : 0,
                    requests.get(i).transactionData()
            ));
        }
        long decisionStartedAt = System.nanoTime();
        List<BatchOutcome<DecisionResponse>> decisions = decisionClient.decideBatch(decisionRequests, fraudBatchExecutor);
        long decisionMs = elapsedMillis(decisionStartedAt);

        // 4. Same result, fraud event and log line per transaction as evaluate().
        for (int k = 0; k < toDecide.size(); k++) {
            int i = toDecide.get(k);
            FraudEvaluationRequest request = requests.get(i);
            BatchOutcome<DecisionResponse> decision = decisions.get(k);
            PreparedEvaluation p = prepared[i];
            withRequestId(request, batchRequestId, () -> {
                if (decision.error() != null) {
                    results[i] = failure(request, decision.error());
                    return;
                }
                try {
                    results[i] = success(request, complete(request, p, decision.value(), scoringMs, decisionMs));
                } catch (RuntimeException ex) {
                    results[i] = failure(request, ex);
                }
            });
        }
        return List.of(results);
    }

    /** Steps of evaluate() before the Scoring call, for one non-duplicate transaction. */
    private PreparedEvaluation prepare(FraudEvaluationRequest request, ActiveRuleCache.Snapshot ruleSnapshot) {
        long startedAt = System.nanoTime();
        List<ActiveRuleResponse> activeRules = ruleSnapshot.rules();

        long locationStartedAt = System.nanoTime();
        Map<String, Object> scoringTransactionData = buildEnrichedTransactionData(request.transactionData());
        long locationMs = elapsedMillis(locationStartedAt);

        long blacklistStartedAt = System.nanoTime();
        List<ActiveRuleResponse> blacklistVirtualRules = buildMatchedBlacklistRules(scoringTransactionData);
        List<ActiveRuleResponse> combinedRules = new ArrayList<>(activeRules.size() + blacklistVirtualRules.size());
        combinedRules.addAll(activeRules);
        combinedRules.addAll(blacklistVirtualRules);
        long rulesMs = elapsedMillis(blacklistStartedAt);
        log.debug(
                "Using active rule cache transactionId={}, ruleCount={}, blacklistVirtualRuleCount={}",
                request.transactionId(),
                activeRules.size(),
                blacklistVirtualRules.size()
        );
        ScoringRequest scoringRequest = new ScoringRequest(
                request.transactionId(),
                combinedRules,
                scoringTransactionData,
                ruleSnapshot.version(),
                activeRules.size(),
                null
        );
        return new PreparedEvaluation(scoringRequest, null, startedAt, rulesMs, locationMs);
    }

    /** Steps of evaluate() after the Decision call: response, fraud event, log line. */
    private FraudEvaluationResponse complete(FraudEvaluationRequest request, PreparedEvaluation p,
                                             DecisionResponse decisionResponse, long scoringMs, long decisionMs) {
        ScoringResponse scoringResponse = p.scoringResponse();
        Integer totalRiskScore = scoringResponse != null ? scoringResponse.totalRiskScore() : 0;
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
                "Fraud evaluation completed transactionId={}, finalDecision={}, totalRiskScore={}, rulesMs={}, locationMs={}, scoringMs={}, decisionMs={}, eventTriggerMs={}, elapsedMs={}, batch=true",
                request.transactionId(),
                finalDecision,
                totalRiskScore,
                p.rulesMs(),
                p.locationMs(),
                scoringMs,
                decisionMs,
                eventTriggerMs,
                elapsedMillis(p.startedAt())
        );
        return response;
    }

    private record PreparedEvaluation(ScoringRequest scoringRequest, ScoringResponse scoringResponse,
                                      long startedAt, long rulesMs, long locationMs) {
        PreparedEvaluation withScoring(ScoringResponse response) {
            return new PreparedEvaluation(scoringRequest, response, startedAt, rulesMs, locationMs);
        }
    }

    private static FraudEvaluationBatchItem success(FraudEvaluationRequest request, FraudEvaluationResponse response) {
        return new FraudEvaluationBatchItem(request.transactionId(), 200, response, null);
    }

    /** Same status and message GlobalExceptionHandler gives the single call for this error. */
    private static FraudEvaluationBatchItem failure(FraudEvaluationRequest request, RuntimeException ex) {
        if (ex instanceof ExternalServiceException) {
            return new FraudEvaluationBatchItem(request.transactionId(), 502, null, ex.getMessage());
        }
        if (ex instanceof UnsupportedOperationException) {
            return new FraudEvaluationBatchItem(request.transactionId(), 501, null, ex.getMessage());
        }
        log.error("Fraud evaluation failed transactionId={}", request.transactionId(), ex);
        return new FraudEvaluationBatchItem(request.transactionId(), 500, null, "Internal Server Error");
    }

    /** Runs work with this transaction's own correlation id in the logs and fraud event. */
    private static void withRequestId(FraudEvaluationRequest request, String batchRequestId, Runnable work) {
        String requestId = request.correlationId() != null ? request.correlationId() : batchRequestId;
        String previous = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (requestId != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, requestId);
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
     * Looks up this transaction's IP / DEVICE / LOCATION value in the pre-built
     * blacklist index (ActiveBlacklistCache -> BlacklistIndex, rebuilt on each cache
     * refresh) and returns one synthetic "virtual rule" for each blacklist entry that
     * actually matches. Each rule is shaped exactly like before
     * (ruleExpression = "field == 'value'", ruleId = -blacklistId), so Scoring
     * Service's existing RuleEvaluator scores it and persists a hit into the existing
     * MatchedRule table with zero changes to Scoring Service.
     *
     * The old approach created a virtual rule for EVERY blacklist entry on EVERY
     * transaction, so the work and the request payload grew with the blacklist. Here
     * the cost is a few hash lookups regardless of blacklist size. No direct
     * blacklist lookup/call happens during evaluation - this only reads the
     * already-refreshed in-memory index. Matching rules (normalization, numeric
     * comparison, duplicates counted per entry, original ordering) mirror
     * RuleEvaluator, so the resulting score and matched rules are unchanged.
     */
    private List<ActiveRuleResponse> buildMatchedBlacklistRules(Map<String, Object> scoringTransactionData) {
        BlacklistIndex index = activeBlacklistCache.getBlacklistIndex();
        List<BlacklistIndex.IndexedEntry> matches = new ArrayList<>();
        Map<Integer, String> fieldByOrder = new HashMap<>();

        for (Map.Entry<String, String> typeToField : BLACKLIST_TYPE_TO_FIELD.entrySet()) {
            String field = typeToField.getValue();
            for (BlacklistIndex.IndexedEntry hit : index.match(typeToField.getKey(), scoringTransactionData.get(field))) {
                matches.add(hit);
                fieldByOrder.put(hit.order(), field);
            }
        }
        if (matches.isEmpty()) {
            return List.of();
        }
        matches.sort(java.util.Comparator.comparingInt(BlacklistIndex.IndexedEntry::order));

        List<ActiveRuleResponse> virtualRules = new ArrayList<>(matches.size());
        for (BlacklistIndex.IndexedEntry hit : matches) {
            String field = fieldByOrder.get(hit.order());
            String type = hit.entry().type().toUpperCase(Locale.ROOT);
            String ruleExpression = field + " == '" + hit.entry().value() + "'";

            virtualRules.add(new ActiveRuleResponse(
                    -hit.entry().blacklistId(),
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
