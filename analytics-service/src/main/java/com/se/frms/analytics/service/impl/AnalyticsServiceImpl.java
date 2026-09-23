package com.se.frms.analytics.service.impl;
import com.se.frms.analytics.dto.AnalyticsSummaryResponse;
import com.se.frms.analytics.dto.ChannelCountResponse;
import com.se.frms.analytics.dto.DailyTransactionVolumeResponse;
import com.se.frms.analytics.dto.DecisionCountResponse;
import com.se.frms.analytics.dto.FraudAnalyticsResponse;
import com.se.frms.analytics.dto.FraudEvent;
import com.se.frms.analytics.dto.FraudTrendResponse;
import com.se.frms.analytics.dto.RulePerformanceResponse;
import com.se.frms.analytics.entity.FraudAnalytics;
import com.se.frms.analytics.repository.FraudAnalyticsRepository;
import com.se.frms.analytics.service.AnalyticsService;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
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
    // A transaction counts as "high risk" once its score reaches the same
    // floor decision-service's default policy uses to start blocking
    // (decision.threshold.review-max defaults to 69, so BLOCK starts at 70).
    // This is independent of whatever the active admin decision policy
    // actually did with the transaction - it's a fixed risk-score cutoff.
    private static final int HIGH_RISK_THRESHOLD = 70;
    private static final String GROUP_BY_DAY = "day";
    private static final String GROUP_BY_WEEK = "week";
    private static final String GROUP_BY_MONTH = "month";
    private static final String GROUP_BY_QUARTER = "quarter";
    private static final String GROUP_BY_YEAR = "year";

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
        analytics.setAmount(extractAmount(event.transactionData()));
        analytics.setChannel(extractChannel(event.transactionData()));
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
    @Transactional
    public void applyDecisionReview(UUID transactionId, String finalDecision) {
        fraudAnalyticsRepository.findByTransactionId(transactionId).ifPresentOrElse(
                analytics -> {
                    String previous = analytics.getFraudDecision();
                    analytics.setFraudDecision(normalizeDecision(finalDecision));
                    fraudAnalyticsRepository.save(analytics);
                    log.info(
                            "Fraud analytics decision updated from manual review transactionId={}, previousDecision={}, newDecision={}",
                            transactionId,
                            previous,
                            analytics.getFraudDecision()
                    );
                },
                () -> log.warn(
                        "Decision-reviewed event ignored - no fraud analytics row yet for transactionId={}",
                        transactionId
                )
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

        // "Fraud Alert" = anything not cleanly ALLOW-ed, i.e. REVIEW + BLOCK.
        long fraudAlertCount = reviewCount + blockCount;

        long highRiskCount = rows.stream()
                .map(FraudAnalytics::getTotalRiskScore)
                .filter(score -> score != null && score >= HIGH_RISK_THRESHOLD)
                .count();

        // Rows saved before the amount column existed have a null amount -
        // treated as 0 here rather than skipped, so a handful of old rows
        // can't silently make this look smaller than it should be.
        BigDecimal blockedAmount = rows.stream()
                .filter(row -> BLOCK.equals(row.getFraudDecision()))
                .map(row -> row.getAmount() != null ? row.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new AnalyticsSummaryResponse(
                total,
                allowCount,
                reviewCount,
                blockCount,
                averageRiskScore,
                fraudAlertCount,
                highRiskCount,
                blockedAmount
        );
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

    @Override
    @Transactional(readOnly = true)
    public List<DailyTransactionVolumeResponse> getDailyTransactionVolume(LocalDate fromDate, LocalDate toDate) {
        DateRange range = resolveDateRange(fromDate, toDate);
        log.info("Fetching daily transaction volume fromDate={}, toDate={}", fromDate, toDate);
        return fraudAnalyticsRepository.sumAmountByDay(range.from(), range.to())
                .stream()
                .map(row -> new DailyTransactionVolumeResponse(row.getDay().toLocalDate(), row.getTotalAmount()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChannelCountResponse> getTransactionsByChannel(LocalDate fromDate, LocalDate toDate) {
        DateRange range = resolveDateRange(fromDate, toDate);
        log.info("Fetching transactions by channel fromDate={}, toDate={}", fromDate, toDate);
        return fraudAnalyticsRepository.countByChannel(range.from(), range.to())
                .stream()
                .map(row -> new ChannelCountResponse(
                        row.getChannel() != null ? row.getChannel() : "UNKNOWN",
                        row.getTransactionCount()
                ))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FraudTrendResponse> getFraudTrend(String groupBy, LocalDate fromDate, LocalDate toDate) {
        DateRange range = resolveDateRange(fromDate, toDate);
        String normalizedGroupBy = normalizeGroupBy(groupBy);
        log.info(
                "Fetching fraud trend groupBy={}, fromDate={}, toDate={}",
                normalizedGroupBy,
                fromDate,
                toDate
        );
        // groupBy is validated against a fixed allow-list above and only ever
        // used to pick one of three hardcoded repository queries below - it
        // is never interpolated into SQL text.
        List<FraudAnalyticsRepository.TrendProjection> rows = switch (normalizedGroupBy) {
            case GROUP_BY_DAY -> fraudAnalyticsRepository.getFraudTrendByDay(range.from(), range.to());
            case GROUP_BY_WEEK -> fraudAnalyticsRepository.getFraudTrendByWeek(range.from(), range.to());
            case GROUP_BY_QUARTER -> fraudAnalyticsRepository.getFraudTrendByQuarter(range.from(), range.to());
            case GROUP_BY_YEAR -> fraudAnalyticsRepository.getFraudTrendByYear(range.from(), range.to());
            default -> fraudAnalyticsRepository.getFraudTrendByMonth(range.from(), range.to());
        };
        return rows.stream()
                .map(row -> new FraudTrendResponse(row.getPeriod(), row.getFraudAlertCount(), row.getBlockedCount()))
                .toList();
    }

    private String normalizeGroupBy(String groupBy) {
        String normalized = groupBy == null ? GROUP_BY_MONTH : groupBy.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case GROUP_BY_DAY -> GROUP_BY_DAY;
            case GROUP_BY_WEEK -> GROUP_BY_WEEK;
            case GROUP_BY_QUARTER -> GROUP_BY_QUARTER;
            case GROUP_BY_YEAR -> GROUP_BY_YEAR;
            default -> GROUP_BY_MONTH;
        };
    }

    // transactionData is a generic Map<String,Object> deserialized from JSON,
    // so "amount" can come through as a Double, Integer, or String depending
    // on how the original request supplied it - BigDecimal's String
    // constructor handles all of those uniformly. Returns null (rather than
    // throwing) for a missing/malformed value so one bad event never breaks
    // saving analytics for that transaction.
    private BigDecimal extractAmount(Map<String, Object> transactionData) {
        if (transactionData == null) {
            return null;
        }
        Object value = transactionData.get("amount");
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException ex) {
            log.warn("Unable to parse transaction amount from event payload: {}", value);
            return null;
        }
    }

    private String extractChannel(Map<String, Object> transactionData) {
        if (transactionData == null) {
            return null;
        }
        Object value = transactionData.get("channel");
        return value == null ? null : value.toString();
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
