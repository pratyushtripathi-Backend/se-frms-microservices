package com.se.frms.scoring.service.impl;

import com.se.frms.scoring.dto.MatchedRuleHistoryResponse;
import com.se.frms.scoring.dto.MatchedRuleResponse;
import com.se.frms.scoring.dto.RuleEvaluationResult;
import com.se.frms.scoring.dto.ScoringHistoryResponse;
import com.se.frms.scoring.dto.ScoringRequest;
import com.se.frms.scoring.dto.ScoringResponse;
import com.se.frms.scoring.entity.MatchedRule;
import com.se.frms.scoring.entity.Scoring;
import com.se.frms.scoring.evaluator.RuleEvaluator;
import com.se.frms.scoring.exception.ScoringNotFoundException;
import com.se.frms.scoring.repository.MatchedRuleRepository;
import com.se.frms.scoring.repository.ScoringRepository;
import com.se.frms.scoring.service.ScoringPersistenceService;
import com.se.frms.scoring.service.ScoringService;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScoringServiceImpl implements ScoringService {

    private final ScoringRepository scoringRepository;
    private final MatchedRuleRepository matchedRuleRepository;
    private final ScoringPersistenceService scoringPersistenceService;
    private final RuleEvaluator ruleEvaluator;

    @Override
    public ScoringResponse process(ScoringRequest request) {

        long startedAt = System.nanoTime();

        log.info(
                "Scoring started transactionId={}, activeRuleCount={}",
                request.transactionId(),
                request.activeRules().size()
        );

        List<RuleEvaluationResult> matchedResults = request.activeRules()
                .stream()
                .map(rule -> ruleEvaluator.evaluate(
                        rule,
                        request.transactionData()
                ))
                .filter(RuleEvaluationResult::matched)
                .toList();

        int totalRiskScore = matchedResults.stream()
                .mapToInt(result ->
                        result.calculatedScore() != null
                                ? result.calculatedScore()
                                : 0
                )
                .sum();

        UUID scoringId = UUID.randomUUID();

        scoringPersistenceService.saveScoring(
                scoringId,
                request.transactionId(),
                totalRiskScore,
                matchedResults
        );

        ScoringResponse response = new ScoringResponse(
                scoringId,
                request.transactionId(),
                totalRiskScore,

                matchedResults.stream()
                        .map(result ->
                                mapToResponse(
                                        result.rule(),
                                        result.calculatedScore()
                                )
                        )
                        .toList(),

                buildTriggeredRules(matchedResults)
        );

        log.info(
                "Scoring calculated transactionId={}, scoringId={}, " +
                        "matchedRuleCount={}, totalRiskScore={}, elapsedMs={}",
                request.transactionId(),
                scoringId,
                matchedResults.size(),
                totalRiskScore,
                elapsedMillis(startedAt)
        );

        return response;
    }

    @Override
    public ScoringResponse getByScoringId(UUID scoringId) {

        Scoring scoring = scoringRepository.findById(scoringId)
                .orElseThrow(() ->
                        new ScoringNotFoundException(
                                "Scoring not found for id: " + scoringId
                        )
                );

        return toResponse(
                scoring,
                matchedRuleRepository
                        .findByScoring_Id(scoringId)
                        .orElse(null)
        );
    }

    @Override
    public ScoringResponse getLatestByTransactionId(UUID transactionId) {

        Scoring scoring =
                scoringRepository
                        .findTopByTransactionIdOrderByCreatedDateDesc(transactionId)
                        .orElseThrow(() ->
                                new ScoringNotFoundException(
                                        "No scoring found for transactionId: "
                                                + transactionId
                                )
                        );

        return toResponse(
                scoring,
                matchedRuleRepository
                        .findByScoring_Id(scoring.getId())
                        .orElse(null)
        );
    }

    @Override
    public List<ScoringResponse> getHistoryByTransactionId(
            UUID transactionId
    ) {

        List<Scoring> scorings =
                scoringRepository
                        .findByTransactionIdOrderByCreatedDateDesc(transactionId);

        if (scorings.isEmpty()) {
            throw new ScoringNotFoundException(
                    "No scoring found for transactionId: " + transactionId
            );
        }

        return scorings.stream()
                .map(scoring ->
                        toResponse(
                                scoring,
                                matchedRuleRepository
                                        .findByScoring_Id(scoring.getId())
                                        .orElse(null)
                        )
                )
                .toList();
    }

    /**
     * Builds ScoringResponse from one Scoring record
     * and one consolidated MatchedRule record.
     *
     * MatchedRule contains rule details as JSONB arrays.
     *
     * The JSONB arrays are converted into individual
     * MatchedRuleResponse objects so that each rule
     * keeps its code, name, expression and scores together.
     */
    private ScoringResponse toResponse(
            Scoring scoring,
            MatchedRule matchedRule
    ) {

        List<MatchedRuleResponse> matchedRuleResponses =
                mapToResponse(matchedRule);

        return new ScoringResponse(
                scoring.getId(),
                scoring.getTransactionId(),
                scoring.getTotalRiskScore(),
                matchedRuleResponses,
                buildTriggeredRulesFromMatchedRule(matchedRule)
        );
    }

    /**
     * Converts one consolidated MatchedRule entity
     * into multiple MatchedRuleResponse objects.
     *
     * Example:
     *
     * ruleCodes       = [A, B, C]
     * ruleNames       = [NameA, NameB, NameC]
     * ruleExpressions = [ExpA, ExpB, ExpC]
     * ruleScores      = [20, 30, 40]
     * calculatedScores= [20, 30, 40]
     *
     * Result:
     *
     * [
     *   {
     *     ruleCode: A,
     *     ruleName: NameA,
     *     ruleExpression: ExpA,
     *     ruleScore: 20,
     *     calculatedScore: 20
     *   },
     *   ...
     * ]
     */
    private List<MatchedRuleResponse> mapToResponse(
            MatchedRule matchedRule
    ) {

        if (matchedRule == null) {
            return Collections.emptyList();
        }

        List<String> ruleCodes =
                nullSafe(matchedRule.getRuleCodes());

        List<String> ruleNames =
                nullSafe(matchedRule.getRuleNames());

        List<String> ruleExpressions =
                nullSafe(matchedRule.getRuleExpressions());

        List<Integer> ruleScores =
                nullSafe(matchedRule.getRuleScores());

        List<Integer> calculatedScores =
                nullSafe(matchedRule.getCalculatedScores());

        List<MatchedRuleResponse> responses = new ArrayList<>();

        for (int i = 0; i < ruleCodes.size(); i++) {

            responses.add(
                    new MatchedRuleResponse(
                            ruleCodes.get(i),

                            i < ruleNames.size()
                                    ? ruleNames.get(i)
                                    : null,

                            i < ruleExpressions.size()
                                    ? ruleExpressions.get(i)
                                    : null,

                            i < ruleScores.size()
                                    ? ruleScores.get(i)
                                    : null,

                            i < calculatedScores.size()
                                    ? calculatedScores.get(i)
                                    : null
                    )
            );
        }

        return responses;
    }

    /**
     * Converts an individual evaluated rule into
     * one MatchedRuleResponse object.
     */
    private MatchedRuleResponse mapToResponse(
            com.se.frms.scoring.dto.RuleEvaluationRequest rule,
            Integer calculatedScore
    ) {

        return new MatchedRuleResponse(
                rule.ruleCode(),
                rule.ruleName(),
                rule.ruleExpression(),
                rule.ruleScore(),
                calculatedScore
        );
    }

    private <T> List<T> nullSafe(List<T> values) {

        return values == null
                ? Collections.emptyList()
                : values;
    }

    /**
     * Triggered rules for process() response.
     */
    private Map<String, Object> buildTriggeredRules(
            List<RuleEvaluationResult> matchedResults
    ) {

        return matchedResults.stream()
                .collect(Collectors.toMap(
                        result -> result.rule().ruleCode(),
                        RuleEvaluationResult::calculatedScore,
                        (left, right) -> left
                ));
    }

    /**
     * Triggered rules for already persisted consolidated MatchedRule.
     *
     * Keeps all rule codes and calculated scores together.
     */
    private Map<String, Object> buildTriggeredRulesFromMatchedRule(
            MatchedRule matchedRule
    ) {

        if (matchedRule == null) {
            return Collections.emptyMap();
        }

        return Map.of(
                "ruleCodes",
                nullSafe(matchedRule.getRuleCodes()),

                "calculatedScores",
                nullSafe(matchedRule.getCalculatedScores())
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MatchedRuleHistoryResponse> getAllMatchedRules(
            Integer year,
            LocalDate startDate,
            LocalDate endDate,
            Pageable pageable
    ) {

        log.info(
                "Fetching matched rules page={}, size={}, year={}, " +
                        "startDate={}, endDate={}",
                pageable.getPageNumber(),
                pageable.getPageSize(),
                year,
                startDate,
                endDate
        );

        Page<Scoring> scoringPage =
                scoringRepository.findAll(
                        buildCreatedDateFilter(
                                year,
                                startDate,
                                endDate
                        ),
                        pageable
                );

        List<MatchedRuleHistoryResponse> content =
                scoringPage.getContent()
                        .stream()
                        .map(scoring -> {

                            MatchedRule matchedRule =
                                    matchedRuleRepository
                                            .findByScoring_Id(scoring.getId())
                                            .orElse(null);

                            if (matchedRule == null) {
                                return null;
                            }

                            List<MatchedRuleResponse> ruleResponses =
                                    mapToResponse(matchedRule);

                            return mapToHistoryResponse(
                                    scoring,
                                    ruleResponses
                            );
                        })
                        .filter(java.util.Objects::nonNull)
                        .toList();

        return new PageImpl<>(
                content,
                pageable,
                scoringPage.getTotalElements()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ScoringHistoryResponse> getAllScorings(
            Integer year,
            LocalDate startDate,
            LocalDate endDate,
            Pageable pageable
    ) {

        log.info(
                "Fetching scorings page={}, size={}, year={}, " +
                        "startDate={}, endDate={}",
                pageable.getPageNumber(),
                pageable.getPageSize(),
                year,
                startDate,
                endDate
        );

        return scoringRepository
                .findAll(
                        buildCreatedDateFilter(
                                year,
                                startDate,
                                endDate
                        ),
                        pageable
                )
                .map(this::mapToScoringHistoryResponse);
    }

    private Specification<Scoring> buildCreatedDateFilter(
            Integer year,
            LocalDate startDate,
            LocalDate endDate
    ) {

        return (root, query, criteriaBuilder) -> {

            List<Predicate> predicates = new ArrayList<>();

            if (year != null) {

                predicates.add(
                        criteriaBuilder.between(
                                root.get("createdDate"),
                                LocalDateTime.of(
                                        year,
                                        1,
                                        1,
                                        0,
                                        0,
                                        0
                                ),
                                LocalDateTime.of(
                                        year,
                                        12,
                                        31,
                                        23,
                                        59,
                                        59
                                )
                        )
                );
            }

            if (startDate != null) {

                predicates.add(
                        criteriaBuilder.greaterThanOrEqualTo(
                                root.get("createdDate"),
                                startDate.atStartOfDay()
                        )
                );
            }

            if (endDate != null) {

                predicates.add(
                        criteriaBuilder.lessThanOrEqualTo(
                                root.get("createdDate"),
                                endDate.atTime(23, 59, 59)
                        )
                );
            }

            return criteriaBuilder.and(
                    predicates.toArray(new Predicate[0])
            );
        };
    }

    private ScoringHistoryResponse mapToScoringHistoryResponse(
            Scoring scoring
    ) {

        return new ScoringHistoryResponse(
                scoring.getId(),
                scoring.getTransactionId(),
                scoring.getTotalRiskScore(),
                scoring.getStatus(),
                scoring.getCreatedBy(),
                scoring.getCreatedDate(),
                scoring.getUpdatedAt()
        );
    }

    private MatchedRuleHistoryResponse mapToHistoryResponse(
            Scoring scoring,
            List<MatchedRuleResponse> matchedRules
    ) {

        return new MatchedRuleHistoryResponse(
                scoring.getId(),
                scoring.getId(),
                scoring.getTransactionId(),
                matchedRules,
                scoring.getStatus(),
                scoring.getCreatedBy(),
                scoring.getCreatedDate(),
                scoring.getUpdatedAt()
        );
    }

    private long elapsedMillis(long startedAt) {

        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}