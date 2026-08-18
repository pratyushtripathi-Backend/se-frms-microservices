package com.se.frms.scoring.evaluator;

import static org.assertj.core.api.Assertions.assertThat;

import com.se.frms.scoring.dto.RuleEvaluationRequest;
import com.se.frms.scoring.dto.RuleEvaluationResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleEvaluatorTest {

    private final RuleEvaluator ruleEvaluator = new RuleEvaluator();

    @Test
    void shouldMatchExplicitRuleCodeAndUseConfiguredRuleScore() {
        RuleEvaluationRequest rule = rule("NEW_DEVICE", 20);

        RuleEvaluationResult result = ruleEvaluator.evaluate(
                rule,
                Map.of("matchedRuleCodes", List.of("NEW_DEVICE"))
        );

        assertThat(result.matched()).isTrue();
        assertThat(result.calculatedScore()).isEqualTo(20);
    }

    @Test
    void shouldMatchHighAmountUsingConfiguredThresholdFromDynamicTransactionData() {
        RuleEvaluationRequest rule = rule("HIGH_AMOUNT", 30);

        RuleEvaluationResult result = ruleEvaluator.evaluate(
                rule,
                Map.of("amount", 5000, "highAmountThreshold", 1000)
        );

        assertThat(result.matched()).isTrue();
        assertThat(result.calculatedScore()).isEqualTo(30);
    }

    @Test
    void shouldNotMatchInactiveRule() {
        RuleEvaluationRequest rule = new RuleEvaluationRequest(
                1,
                null,
                "NEW_DEVICE",
                "New Device",
                null,
                null,
                null,
                20,
                false
        );

        RuleEvaluationResult result = ruleEvaluator.evaluate(
                rule,
                Map.of("matchedRuleCodes", List.of("NEW_DEVICE"))
        );

        assertThat(result.matched()).isFalse();
        assertThat(result.calculatedScore()).isZero();
    }

    @Test
    void shouldMatchRuleExpressionAgainstDynamicTransactionData() {
        RuleEvaluationRequest rule = new RuleEvaluationRequest(
                1,
                null,
                "AMOUNT_EXPRESSION",
                "Amount Expression",
                null,
                "{\"field\":\"amount\",\"operator\":\">=\",\"value\":1000}",
                null,
                25,
                true
        );

        RuleEvaluationResult result = ruleEvaluator.evaluate(
                rule,
                Map.of("amount", 5000)
        );

        assertThat(result.matched()).isTrue();
        assertThat(result.calculatedScore()).isEqualTo(25);
    }

    private RuleEvaluationRequest rule(String ruleCode, Integer ruleScore) {
        return new RuleEvaluationRequest(
                1,
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
