package com.se.frms.scoring.service.impl;

import com.se.frms.scoring.dto.MatchedRuleHistoryResponse;
import com.se.frms.scoring.dto.ScoringHistoryResponse;
import com.se.frms.scoring.dto.MatchedRuleResponse;
import com.se.frms.scoring.dto.RuleEvaluationResult;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
                .map(rule -> ruleEvaluator.evaluate(rule, request.transactionData()))
                .filter(RuleEvaluationResult::matched)
                .toList();

        int totalRiskScore = matchedResults.stream()
                .mapToInt(result -> result.calculatedScore() != null ? result.calculatedScore() : 0)
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
                        .map(result -> mapToResponse(result.rule(), result.calculatedScore()))
                        .toList(),
                buildTriggeredRules(matchedResults)
        );

        log.info(
                "Scoring calculated transactionId={}, scoringId={}, matchedRuleCount={}, totalRiskScore={}, elapsedMs={}",
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
                .orElseThrow(() -> new ScoringNotFoundException("Scoring not found for id: " + scoringId));
        List<MatchedRule> matchedRules = matchedRuleRepository.findByScoring_Id(scoringId);
        return toResponse(scoring, matchedRules);
    }

    @Override
    public ScoringResponse getLatestByTransactionId(UUID transactionId) {
        Scoring scoring = scoringRepository.findTopByTransactionIdOrderByCreatedDateDesc(transactionId)
                .orElseThrow(() -> new ScoringNotFoundException("No scoring found for transactionId: " + transactionId));
        List<MatchedRule> matchedRules = matchedRuleRepository.findByScoring_Id(scoring.getId());
        return toResponse(scoring, matchedRules);
    }

    @Override
    public List<ScoringResponse> getHistoryByTransactionId(UUID transactionId) {
        List<Scoring> scorings = scoringRepository.findByTransactionIdOrderByCreatedDateDesc(transactionId);
        if (scorings.isEmpty()) {
            throw new ScoringNotFoundException("No scoring found for transactionId: " + transactionId);
        }
        return scorings.stream()
                .map(scoring -> toResponse(scoring, matchedRuleRepository.findByScoring_Id(scoring.getId())))
                .toList();
    }

    /**
     * Central place that builds a ScoringResponse from a Scoring + its
     * MatchedRule list. Used by process() AND all the GET methods, so
     * there is only one mapping to maintain.
     */
    private ScoringResponse toResponse(Scoring scoring, List<MatchedRule> matchedRules) {
        List<MatchedRuleResponse> matchedRuleResponses = matchedRules.stream()
                .map(this::mapToResponse)
                .toList();

        Map<String, Object> triggeredRules = matchedRuleResponses.stream()
                .collect(Collectors.toMap(
                        MatchedRuleResponse::ruleCode,
                        MatchedRuleResponse::calculatedScore,
                        (left, right) -> left
                ));

        return new ScoringResponse(
                scoring.getId(),
                scoring.getTransactionId(),
                scoring.getTotalRiskScore(),
                matchedRuleResponses,
                triggeredRules
        );
    }

    private Map<String, Object> buildTriggeredRules(List<RuleEvaluationResult> matchedResults) {
        return matchedResults.stream()
                .collect(Collectors.toMap(
                        result -> result.rule().ruleCode(),
                        RuleEvaluationResult::calculatedScore,
                        (left, right) -> left
                ));
    }

    private MatchedRuleResponse mapToResponse(
            com.se.frms.scoring.dto.RuleEvaluationRequest rule,
            Integer calculatedScore
    ) {
        return new MatchedRuleResponse(
                rule.ruleId(),
                rule.ruleCode(),
                rule.ruleName(),
                rule.ruleExpression(),
                rule.ruleScore(),
                calculatedScore
        );
    }

    private MatchedRuleResponse mapToResponse(MatchedRule matchedRule) {
        return new MatchedRuleResponse(
                matchedRule.getRuleId(),
                matchedRule.getRuleCode(),
                matchedRule.getRuleName(),
                matchedRule.getRuleExpression(),
                matchedRule.getRuleScore(),
                matchedRule.getCalculatedScore()
        );
    }


    @Override
    @Transactional(readOnly = true)
    public Page<MatchedRuleHistoryResponse> getAllMatchedRules(Integer page, Integer size) {
        Sort sort = Sort.by(Sort.Direction.DESC, "createdDate");

        // No size passed -> caller wants EVERYTHING, no pagination.
        if (size == null) {
            List<MatchedRuleHistoryResponse> all = matchedRuleRepository.findAll(sort)
                    .stream()
                    .map(this::mapToHistoryResponse)
                    .toList();
            return new PageImpl<>(all, Pageable.unpaged(), all.size());
        }

        Pageable pageable = PageRequest.of(page != null ? page : 0, size, sort);
        return matchedRuleRepository.findAllByOrderByCreatedDateDesc(pageable)
                .map(this::mapToHistoryResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ScoringHistoryResponse> getAllScorings(Integer page, Integer size) {
        Sort sort = Sort.by(Sort.Direction.DESC, "createdDate");

        // No size passed -> caller wants EVERYTHING, no pagination.
        if (size == null) {
            List<ScoringHistoryResponse> all = scoringRepository.findAll(sort)
                    .stream()
                    .map(this::mapToScoringHistoryResponse)
                    .toList();
            return new PageImpl<>(all, Pageable.unpaged(), all.size());
        }

        Pageable pageable = PageRequest.of(page != null ? page : 0, size, sort);
        return scoringRepository.findAll(pageable)
                .map(this::mapToScoringHistoryResponse);
    }

    private ScoringHistoryResponse mapToScoringHistoryResponse(Scoring scoring) {
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

    private MatchedRuleHistoryResponse mapToHistoryResponse(MatchedRule matchedRule) {
        return new MatchedRuleHistoryResponse(
                matchedRule.getId(),
                matchedRule.getScoring().getId(),
                matchedRule.getScoring().getTransactionId(),
                matchedRule.getRuleId(),
                matchedRule.getRuleCode(),
                matchedRule.getRuleName(),
                matchedRule.getRuleExpression(),
                matchedRule.getRuleScore(),
                matchedRule.getCalculatedScore(),
                matchedRule.getStatus(),
                matchedRule.getCreatedBy(),
                matchedRule.getCreatedDate(),
                matchedRule.getUpdatedAt()
        );
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
