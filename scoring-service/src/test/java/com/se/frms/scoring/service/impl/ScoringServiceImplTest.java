package com.se.frms.scoring.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.se.frms.scoring.dto.RuleEvaluationRequest;
import com.se.frms.scoring.dto.ScoringRequest;
import com.se.frms.scoring.dto.ScoringResponse;
import com.se.frms.scoring.entity.MatchedRule;
import com.se.frms.scoring.entity.Scoring;
import com.se.frms.scoring.evaluator.RuleEvaluator;
import com.se.frms.scoring.repository.MatchedRuleRepository;
import com.se.frms.scoring.repository.ScoringRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScoringServiceImplTest {

    @Mock
    private ScoringRepository scoringRepository;

    @Mock
    private MatchedRuleRepository matchedRuleRepository;

    private final RuleEvaluator ruleEvaluator = new RuleEvaluator();

    @InjectMocks
    private ScoringServiceImpl scoringService;

    @Test
    void shouldCalculateTotalRiskScoreFromMatchedRules() {
        scoringService = new ScoringServiceImpl(scoringRepository, matchedRuleRepository, ruleEvaluator);
        when(scoringRepository.save(any(Scoring.class))).thenAnswer(invocation -> {
            Scoring scoring = invocation.getArgument(0);
            scoring.setId(UUID.randomUUID());
            return scoring;
        });
        when(matchedRuleRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UUID transactionId = UUID.randomUUID();
        ScoringResponse response = scoringService.process(new ScoringRequest(
                transactionId,
                List.of(
                        rule(1, "HIGH_AMOUNT", 30),
                        rule(2, "NEW_DEVICE", 20),
                        rule(3, "UNMATCHED", 50),
                        expressionRule(4, "FOREIGN_CURRENCY", "currency != INR", 20),
                        expressionRule(5, "SUSPICIOUS_IP", "ipRiskScore >= 70", 50),
                        expressionRule(6, "BLOCKED_DEVICE", "deviceRiskScore >= 90", 60)
                ),
                Map.of(
                        "amount", 5000,
                        "highAmountThreshold", 1000,
                        "matchedRuleCodes", List.of("NEW_DEVICE"),
                        "currency", "USD",
                        "ipRiskScore", 80,
                        "deviceRiskScore", 40
                )
        ));

        assertThat(response.transactionId()).isEqualTo(transactionId);
        assertThat(response.totalRiskScore()).isEqualTo(120);
        assertThat(response.matchedRules()).hasSize(4);
        assertThat(response.triggeredRules()).containsEntry("HIGH_AMOUNT", 30);
        assertThat(response.triggeredRules()).containsEntry("NEW_DEVICE", 20);
        assertThat(response.triggeredRules()).containsEntry("FOREIGN_CURRENCY", 20);
        assertThat(response.triggeredRules()).containsEntry("SUSPICIOUS_IP", 50);
        assertThat(response.triggeredRules()).doesNotContainKey("BLOCKED_DEVICE");
    }

    private RuleEvaluationRequest rule(Integer ruleId, String ruleCode, Integer ruleScore) {
        return new RuleEvaluationRequest(
                ruleId,
                null,
                ruleCode,
                ruleCode,
                null,
                null,
                null,
                ruleScore,
                true
        );
    }

    private RuleEvaluationRequest expressionRule(
            Integer ruleId,
            String ruleCode,
            String expression,
            Integer ruleScore
    ) {
        return new RuleEvaluationRequest(
                ruleId,
                null,
                ruleCode,
                ruleCode,
                null,
                expression,
                null,
                ruleScore,
                true
        );
    }
}
