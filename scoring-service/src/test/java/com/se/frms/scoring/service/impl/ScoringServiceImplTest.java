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
                        rule(3, "UNMATCHED", 50)
                ),
                Map.of(
                        "amount", 5000,
                        "highAmountThreshold", 1000,
                        "matchedRuleCodes", List.of("NEW_DEVICE")
                )
        ));

        assertThat(response.transactionId()).isEqualTo(transactionId);
        assertThat(response.totalRiskScore()).isEqualTo(50);
        assertThat(response.matchedRules()).hasSize(2);
        assertThat(response.triggeredRules()).containsEntry("HIGH_AMOUNT", 30);
        assertThat(response.triggeredRules()).containsEntry("NEW_DEVICE", 20);
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
}
