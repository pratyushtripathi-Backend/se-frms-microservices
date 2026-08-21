package com.se.frms.scoring.service.impl;

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
import com.se.frms.scoring.service.ScoringService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScoringServiceImpl implements ScoringService {

    private static final String SYSTEM_USER = "SCORING_SERVICE";

    private final ScoringRepository scoringRepository;
    private final MatchedRuleRepository matchedRuleRepository;
    private final RuleEvaluator ruleEvaluator;

    @Override
    @Transactional
    public ScoringResponse process(ScoringRequest request) {
        long startedAt = System.nanoTime();
        log.info(
                "Scoring started transactionId={}, activeRuleCount={}",
                request.transactionId(),
                request.activeRules().size()
        );

        Scoring scoring = new Scoring();
        scoring.setTransactionId(request.transactionId());
        scoring.setTotalRiskScore(0);
        scoring.setStatus(true);
        scoring.setCreatedBy(SYSTEM_USER);
        scoring = scoringRepository.save(scoring);

        Scoring savedScoring = scoring;
        List<RuleEvaluationResult> matchedResults = request.activeRules()
                .stream()
                .map(rule -> ruleEvaluator.evaluate(rule, request.transactionData()))
                .filter(RuleEvaluationResult::matched)
                .toList();

        List<MatchedRule> matchedRules = matchedResults.stream()
                .map(result -> buildMatchedRule(savedScoring, result))
                .toList();
        matchedRuleRepository.saveAll(matchedRules);

        int totalRiskScore = matchedResults.stream()
                .mapToInt(result -> result.calculatedScore() != null ? result.calculatedScore() : 0)
                .sum();

        scoring.setTotalRiskScore(totalRiskScore);
        scoring = scoringRepository.save(scoring);

        ScoringResponse response = toResponse(scoring, matchedRules);

        log.info(
                "Scoring completed transactionId={}, scoringId={}, matchedRuleCount={}, totalRiskScore={}, elapsedMs={}",
                request.transactionId(),
                scoring.getId(),
                matchedRules.size(),
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

    private MatchedRule buildMatchedRule(Scoring scoring, RuleEvaluationResult result) {
        MatchedRule matchedRule = new MatchedRule();
        matchedRule.setScoring(scoring);
        matchedRule.setRuleId(result.rule().ruleId());
        matchedRule.setRuleCode(result.rule().ruleCode());
        matchedRule.setRuleName(result.rule().ruleName());
        matchedRule.setRuleExpression(result.rule().ruleExpression());
        matchedRule.setRuleScore(result.rule().ruleScore());
        matchedRule.setCalculatedScore(result.calculatedScore());
        matchedRule.setStatus(true);
        matchedRule.setCreatedBy(SYSTEM_USER);
        return matchedRule;
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

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
