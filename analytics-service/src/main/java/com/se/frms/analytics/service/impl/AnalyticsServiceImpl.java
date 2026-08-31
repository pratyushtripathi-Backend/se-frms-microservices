package com.se.frms.analytics.service.impl;
import com.se.frms.analytics.dto.AnalyticsSummaryResponse;
import com.se.frms.analytics.dto.DecisionCountResponse;
import com.se.frms.analytics.dto.FraudAnalyticsResponse;
import com.se.frms.analytics.dto.FraudEvent;
import com.se.frms.analytics.dto.RulePerformanceResponse;
import com.se.frms.analytics.entity.FraudAnalytics;
import com.se.frms.analytics.repository.FraudAnalyticsRepository;
import com.se.frms.analytics.service.AnalyticsService;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsServiceImpl implements AnalyticsService {

    private static final String ALLOW = "ALLOW";
    private static final String REVIEW = "REVIEW";
    private static final String BLOCK = "BLOCK";
    private static final String ANALYTICS_SERVICE = "ANALYTICS_SERVICE";

    private final FraudAnalyticsRepository fraudAnalyticsRepository;

    @Override
    @Transactional
    public void handleFraudEvent(FraudEvent event) {
        long startedAt = System.nanoTime();
        FraudAnalytics analytics = fraudAnalyticsRepository.findByTransactionId(event.transactionId())
                .orElseGet(FraudAnalytics::new);
        analytics.setTransactionId(event.transactionId());
        analytics.setScoringId(requireId(event.scoringId(), "scoringId", event.transactionId()));
        analytics.setDecisionId(requireId(event.decisionId(), "decisionId", event.transactionId()));
        analytics.setTotalRiskScore(event.totalRiskScore() != null ? event.totalRiskScore() : 0);
        analytics.setFraudDecision(normalizeDecision(event.fraudDecision()));
        analytics.setTriggeredRules(event.triggeredRules());
        analytics.setTransactionData(event.transactionData());
        analytics.setStatus(true);
        analytics.setCreatedBy(ANALYTICS_SERVICE);
        fraudAnalyticsRepository.save(analytics);
        log.info(
                "Fraud analytics saved transactionId={}, fraudDecision={}, totalRiskScore={}, elapsedMs={}",
                event.transactionId(),
                analytics.getFraudDecision(),
                analytics.getTotalRiskScore(),
                elapsedMillis(startedAt)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FraudAnalyticsResponse> getAll(
            Pageable pageable,
            String fraudDecision,
            LocalDate fromDate,
            LocalDate toDate
    ) {
        log.info(
                "Fetching fraud analytics page={}, size={}, fraudDecision={}, fromDate={}, toDate={}",
                pageable.getPageNumber(),
                pageable.getPageSize(),
                fraudDecision,
                fromDate,
                toDate
        );
        return fraudAnalyticsRepository.findAll(buildSpecification(fraudDecision, fromDate, toDate), pageable)
                .map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public FraudAnalyticsResponse getById(UUID analyticsId) {
        log.info("Fetching fraud analytics by analyticsId={}", analyticsId);
        return fraudAnalyticsRepository.findById(analyticsId)
                .map(this::mapToResponse)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Fraud analytics not found: " + analyticsId
                ));
    }

    @Override
    @Transactional(readOnly = true)
    public FraudAnalyticsResponse getByTransactionId(UUID transactionId) {
        log.info("Fetching fraud analytics by transactionId={}", transactionId);
        return fraudAnalyticsRepository.findByTransactionId(transactionId)
                .map(this::mapToResponse)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Fraud analytics not found for transactionId: " + transactionId
                ));
    }

    @Override
    @Transactional(readOnly = true)
    public AnalyticsSummaryResponse getSummary(LocalDate fromDate, LocalDate toDate) {
        DateRange range = resolveDateRange(fromDate, toDate);
        List<FraudAnalytics> rows = fraudAnalyticsRepository.findAll(createdAtBetween(range));
        long total = rows.size();
        long allowCount = rows.stream().filter(row -> ALLOW.equals(row.getFraudDecision())).count();
        long reviewCount = rows.stream().filter(row -> REVIEW.equals(row.getFraudDecision())).count();
        long blockCount = rows.stream().filter(row -> BLOCK.equals(row.getFraudDecision())).count();
        double averageRiskScore = rows.stream()
                .map(FraudAnalytics::getTotalRiskScore)
                .filter(score -> score != null)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0);

        return new AnalyticsSummaryResponse(total, allowCount, reviewCount, blockCount, averageRiskScore);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DecisionCountResponse> getDecisionCounts(LocalDate fromDate, LocalDate toDate) {
        DateRange range = resolveDateRange(fromDate, toDate);
        return List.of(
                new DecisionCountResponse(
                        ALLOW,
                        fraudAnalyticsRepository.countByFraudDecisionAndCreatedAtBetween(ALLOW, range.from(), range.to())
                ),
                new DecisionCountResponse(
                        REVIEW,
                        fraudAnalyticsRepository.countByFraudDecisionAndCreatedAtBetween(REVIEW, range.from(), range.to())
                ),
                new DecisionCountResponse(
                        BLOCK,
                        fraudAnalyticsRepository.countByFraudDecisionAndCreatedAtBetween(BLOCK, range.from(), range.to())
                )
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<RulePerformanceResponse> getRulePerformance(int limit) {
        int effectiveLimit = Math.max(limit, 1);
        Map<String, RuleStats> statsByRule = new HashMap<>();
        fraudAnalyticsRepository.findAll().forEach(row -> {
            if (row.getTriggeredRules() == null) {
                return;
            }
            row.getTriggeredRules().forEach((ruleCode, score) -> {
                RuleStats stats = statsByRule.computeIfAbsent(ruleCode, ignored -> new RuleStats());
                stats.triggerCount++;
                stats.totalScore += toLong(score);
            });
        });

        return statsByRule.entrySet()
                .stream()
                .map(entry -> new RulePerformanceResponse(
                        entry.getKey(),
                        entry.getValue().triggerCount,
                        entry.getValue().totalScore
                ))
                .sorted(Comparator.comparingLong(RulePerformanceResponse::triggerCount).reversed())
                .limit(effectiveLimit)
                .toList();
    }

    private UUID requireId(UUID id, String fieldName, UUID transactionId) {
        if (id == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Fraud event missing " + fieldName + " for transactionId: " + transactionId
            );
        }
        return id;
    }

    private String normalizeDecision(String fraudDecision) {
        String normalized = fraudDecision == null ? "" : fraudDecision.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case ALLOW -> ALLOW;
            case REVIEW -> REVIEW;
            case BLOCK, "DUPLICATE_FRAUD", "DUPLICATE_EXTERNAL_TRANSACTION_ID" -> BLOCK;
            default -> REVIEW;
        };
    }

    private Specification<FraudAnalytics> buildSpecification(
            String fraudDecision,
            LocalDate fromDate,
            LocalDate toDate
    ) {
        DateRange range = resolveDateRange(fromDate, toDate);
        return (root, query, criteriaBuilder) -> {
            Predicate predicate = criteriaBuilder.between(root.get("createdAt"), range.from(), range.to());
            if (StringUtils.hasText(fraudDecision)) {
                predicate = criteriaBuilder.and(
                        predicate,
                        criteriaBuilder.equal(root.get("fraudDecision"), normalizeDecision(fraudDecision))
                );
            }
            query.orderBy(criteriaBuilder.desc(root.get("createdAt")));
            return predicate;
        };
    }

    private Specification<FraudAnalytics> createdAtBetween(DateRange range) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.between(root.get("createdAt"), range.from(), range.to());
    }

    private DateRange resolveDateRange(LocalDate fromDate, LocalDate toDate) {
        LocalDateTime from = fromDate != null
                ? fromDate.atStartOfDay()
                : LocalDate.of(1970, 1, 1).atStartOfDay();
        LocalDateTime to = toDate != null
                ? toDate.plusDays(1).atStartOfDay().minusNanos(1)
                : LocalDateTime.now().plusYears(100);
        return new DateRange(from, to);
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private FraudAnalyticsResponse mapToResponse(FraudAnalytics analytics) {
        return new FraudAnalyticsResponse(
                analytics.getId(),
                analytics.getTransactionId(),
                analytics.getScoringId(),
                analytics.getDecisionId(),
                analytics.getTotalRiskScore(),
                analytics.getFraudDecision(),
                analytics.getTriggeredRules(),
                analytics.getTransactionData(),
                analytics.getStatus(),
                analytics.getCreatedBy(),
                analytics.getCreatedAt(),
                analytics.getUpdatedAt()
        );
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private record DateRange(LocalDateTime from, LocalDateTime to) {
    }

    private static class RuleStats {
        private long triggerCount;
        private long totalScore;
    }
}
