package com.se.frms.scoring.evaluator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.se.frms.scoring.dto.RuleEvaluationRequest;
import com.se.frms.scoring.dto.RuleEvaluationResult;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RuleEvaluator {

    private static final BigDecimal DEFAULT_HIGH_AMOUNT_THRESHOLD = BigDecimal.valueOf(100000);
    private static final String MATCHED_RULE_CODES = "matchedRuleCodes";
    private static final String TRIGGERED_RULE_CODES = "triggeredRuleCodes";
    private static final String FRAUD_SIGNAL = "fraudSignal";
    private static final TypeReference<Map<String, Object>> EXPRESSION_TYPE = new TypeReference<>() {
    };
    private static final Pattern SIMPLE_EXPRESSION = Pattern.compile(
            "^\\s*([A-Za-z][A-Za-z0-9_]*)\\s*(>=|<=|!=|==|=|>|<)\\s*(.+?)\\s*$"
    );

    private final ObjectMapper objectMapper;

    private final Map<String, BiPredicate<RuleEvaluationRequest, Map<String, Object>>> ruleHandlers = Map.of(
            "HIGH_AMOUNT", this::matchesHighAmount,
            "DUPLICATE_FRAUD", this::matchesFraudSignal,
            "DUPLICATE_EXTERNAL_TRANSACTION_ID", this::matchesFraudSignal
    );

    public RuleEvaluator() {
        this(new ObjectMapper());
    }

    public RuleEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RuleEvaluationResult evaluate(RuleEvaluationRequest rule, Map<String, Object> transactionData) {
        boolean matched = isActive(rule) && matches(rule, transactionData);

        return new RuleEvaluationResult(
                rule,
                matched,
                matched ? safeScore(rule.ruleScore()) : 0
        );
    }

    private boolean isActive(RuleEvaluationRequest rule) {
        return !Boolean.FALSE.equals(rule.status());
    }

    private boolean matches(RuleEvaluationRequest rule, Map<String, Object> transactionData) {
        // A configured expression is the source of truth; do not let a legacy
        // rule-code handler match a different condition by accident.
        if (StringUtils.hasText(rule.ruleExpression())) {
            return matchesRuleExpression(rule, transactionData);
        }

        return matchesExplicitRuleCode(rule, transactionData)
                || matchesRuleBooleanFlag(rule, transactionData)
                || matchesRegisteredHandler(rule, transactionData);
    }

    private boolean matchesRegisteredHandler(RuleEvaluationRequest rule, Map<String, Object> transactionData) {
        BiPredicate<RuleEvaluationRequest, Map<String, Object>> handler = ruleHandlers.get(normalize(rule.ruleCode()));
        return handler != null && handler.test(rule, transactionData);
    }

    private boolean matchesRuleExpression(RuleEvaluationRequest rule, Map<String, Object> transactionData) {
        if (!StringUtils.hasText(rule.ruleExpression())) {
            return false;
        }
        try {
            Map<String, Object> expression = objectMapper.readValue(rule.ruleExpression(), EXPRESSION_TYPE);
            return evaluateCondition(
                    Objects.toString(expression.get("field"), ""),
                    Objects.toString(expression.get("operator"), ""),
                    expression.get("value"),
                    transactionData
            );
        } catch (Exception ex) {
            return matchesSimpleExpression(rule.ruleExpression(), transactionData);
        }
    }

    private boolean matchesSimpleExpression(String ruleExpression, Map<String, Object> transactionData) {
        Matcher matcher = SIMPLE_EXPRESSION.matcher(ruleExpression);
        if (!matcher.matches()) {
            return false;
        }

        return evaluateCondition(matcher.group(1), matcher.group(2), stripWrappingQuotes(matcher.group(3)), transactionData);
    }

    private boolean evaluateCondition(String field, String operator, Object expectedValue, Map<String, Object> transactionData) {
        Object actualValue = transactionData.get(field);
        if (!StringUtils.hasText(field) || actualValue == null) {
            return false;
        }

        return switch (normalize(operator)) {
            case "=", "==", "EQUALS" -> valuesEqual(actualValue, expectedValue);
            case "!=", "NOT_EQUALS" -> !valuesEqual(actualValue, expectedValue);
            case ">", ">=", "<", "<=" -> compareNumeric(actualValue, expectedValue, normalize(operator));
            case "IN" -> collectionContains(expectedValue, actualValue.toString());
            case "CONTAINS" -> actualValue.toString().contains(Objects.toString(expectedValue, ""));
            default -> false;
        };
    }

    private String stripWrappingQuotes(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private boolean matchesExplicitRuleCode(RuleEvaluationRequest rule, Map<String, Object> transactionData) {
        return collectionContains(transactionData.get(MATCHED_RULE_CODES), rule.ruleCode())
                || collectionContains(transactionData.get(TRIGGERED_RULE_CODES), rule.ruleCode());
    }

    private boolean matchesRuleBooleanFlag(RuleEvaluationRequest rule, Map<String, Object> transactionData) {
        String normalizedRuleCode = normalize(rule.ruleCode());
        return Boolean.TRUE.equals(transactionData.get(rule.ruleCode()))
                || Boolean.TRUE.equals(transactionData.get(normalizedRuleCode))
                || Boolean.TRUE.equals(transactionData.get(toCamelCase(normalizedRuleCode)));
    }

    private boolean matchesHighAmount(RuleEvaluationRequest rule, Map<String, Object> transactionData) {
        BigDecimal amount = toBigDecimal(transactionData.get("amount"));
        if (amount == null) {
            return false;
        }
        BigDecimal threshold = toBigDecimal(transactionData.get("highAmountThreshold"));
        return amount.compareTo(threshold != null ? threshold : DEFAULT_HIGH_AMOUNT_THRESHOLD) >= 0;
    }

    private boolean matchesFraudSignal(RuleEvaluationRequest rule, Map<String, Object> transactionData) {
        Object fraudSignal = transactionData.get(FRAUD_SIGNAL);
        return fraudSignal != null && normalize(rule.ruleCode()).equals(normalize(fraudSignal.toString()));
    }

    private boolean collectionContains(Object value, String ruleCode) {
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .filter(item -> item != null)
                    .map(Object::toString)
                    .map(this::normalize)
                    .anyMatch(normalize(ruleCode)::equals);
        }
        if (value instanceof String text) {
            return Arrays.stream(text.split(","))
                    .map(String::trim)
                    .map(this::normalize)
                    .anyMatch(normalize(ruleCode)::equals);
        }
        return false;
    }

    private boolean valuesEqual(Object actualValue, Object expectedValue) {
        BigDecimal actualNumber = toBigDecimal(actualValue);
        BigDecimal expectedNumber = toBigDecimal(expectedValue);
        if (actualNumber != null && expectedNumber != null) {
            return actualNumber.compareTo(expectedNumber) == 0;
        }
        return normalize(Objects.toString(actualValue, ""))
                .equals(normalize(Objects.toString(expectedValue, "")));
    }

    private boolean compareNumeric(Object actualValue, Object expectedValue, String operator) {
        BigDecimal actualNumber = toBigDecimal(actualValue);
        BigDecimal expectedNumber = toBigDecimal(expectedValue);
        if (actualNumber == null || expectedNumber == null) {
            return false;
        }
        int comparison = actualNumber.compareTo(expectedNumber);
        return switch (operator) {
            case ">" -> comparison > 0;
            case ">=" -> comparison >= 0;
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            default -> false;
        };
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null || !StringUtils.hasText(value.toString())) {
            return null;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer safeScore(Integer ruleScore) {
        return Math.max(ruleScore != null ? ruleScore : 0, 0);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String toCamelCase(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder();
        boolean upperNext = false;
        for (char character : lower.toCharArray()) {
            if (character == '_') {
                upperNext = true;
                continue;
            }
            builder.append(upperNext ? Character.toUpperCase(character) : character);
            upperNext = false;
        }
        return builder.toString();
    }
}
