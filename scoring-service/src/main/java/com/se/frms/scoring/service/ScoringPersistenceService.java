package com.se.frms.scoring.service;

import com.se.frms.scoring.dto.RuleEvaluationResult;
import com.se.frms.scoring.entity.MatchedRule;
import com.se.frms.scoring.entity.Scoring;
import com.se.frms.scoring.repository.MatchedRuleRepository;
import com.se.frms.scoring.repository.ScoringRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScoringPersistenceService {

    private static final String SYSTEM_USER = "SCORING_SERVICE";

    private final ScoringRepository scoringRepository;
    private final MatchedRuleRepository matchedRuleRepository;

    @Async
    @Transactional
    public void saveScoring(
            UUID scoringId,
            UUID transactionId,
            Integer totalRiskScore,
            List<RuleEvaluationResult> matchedResults
    ) {
        long startedAt = System.nanoTime();
        try {
            Scoring scoring = new Scoring();
            scoring.setId(scoringId);
            scoring.setTransactionId(transactionId);
            scoring.setTotalRiskScore(totalRiskScore);
            scoring.setStatus(true);
            scoring.setCreatedBy(SYSTEM_USER);
            Scoring savedScoring = scoringRepository.save(scoring);
            matchedRuleRepository.save(buildMatchedRule(savedScoring, matchedResults));

            log.info(
                    "Scoring persisted asynchronously transactionId={}, scoringId={}, matchedRuleCount={}, elapsedMs={}",
                    transactionId,
                    scoringId,
                    matchedResults.size(),
                    elapsedMillis(startedAt)
            );
        } catch (RuntimeException ex) {
            log.error(
                    "Async scoring persistence failed transactionId={}, scoringId={}",
                    transactionId,
                    scoringId,
                    ex
            );
        }
    }

    private MatchedRule buildMatchedRule(Scoring scoring, List<RuleEvaluationResult> results) {
        MatchedRule matchedRule = new MatchedRule();
        matchedRule.setScoring(scoring);
        matchedRule.setRuleCodes(results.stream().map(result -> result.rule().ruleCode()).toList());
        matchedRule.setRuleNames(results.stream().map(result -> result.rule().ruleName()).toList());
        matchedRule.setRuleExpressions(results.stream().map(result -> result.rule().ruleExpression()).toList());
        matchedRule.setRuleScores(results.stream().map(result -> result.rule().ruleScore()).toList());
        matchedRule.setCalculatedScores(results.stream().map(RuleEvaluationResult::calculatedScore).toList());
        matchedRule.setStatus(true);
        matchedRule.setCreatedBy(SYSTEM_USER);
        return matchedRule;
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
