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
import java.util.concurrent.ConcurrentHashMap;
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

    // Used by normalize() so that formatting-only differences (e.g. missing/extra
    // spaces around a comma, like "Mumbai,India" vs "Mumbai, India" vs
    // "Mumbai , India") never cause an otherwise-identical value to fail to
    // match. This matters most for LOCATION blacklist values ("City, Country"),
    // where the geocoding provider or an admin typing the blacklist entry could
    // produce slightly different spacing for the same real value - but it applies
    // to any comma-separated value compared via valuesEqual/normalize, not just
    // LOCATION. Purely a formatting normalization: it never changes what the
    // actual value is, only how consistently its spacing is written, so it cannot
    // make an unrelated value match - it only prevents identical values from
    // being missed due to spacing differences.
    private static final Pattern COMMA_SPACING = Pattern.compile("\\s*,\\s*");
    private static final Pattern EXTRA_WHITESPACE = Pattern.compile("\\s+");

    private final ObjectMapper objectMapper;

    /*
     * Parsed form of each rule expression, keyed by the exact expression text.
     * Before, every rule was parsed again for every transaction - and blacklist rules
     * ("field == 'value'", not JSON) threw a JSON parse exception every time before
     * the simple-expression fallback ran. The evaluation itself is unchanged: the same
     * evaluateCondition() runs with the same field / operator / value, and the same
     * fallbacks apply. An edited rule has different text, so it is parsed fresh; old
     * entries are dropped once the map reaches MAX_PARSED_EXPRESSIONS.
     */
    private static final int MAX_PARSED_EXPRESSIONS = 10_000;
    private final Map<String, JsonCondition> jsonConditions = new ConcurrentHashMap<>();
    private final Map<String, SimpleCondition> simpleConditions = new ConcurrentHashMap<>();

    /** JSON expression {"field","operator","value"}; NOT_JSON when the text is not one. */
    private record JsonCondition(boolean json, String field, String operator, Object value) {
        static final JsonCondition NOT_JSON = new JsonCondition(false, null, null, null);
    }

    /** "field op value" expression; NO_MATCH when the text does not have that shape. */
    private record SimpleCondition(boolean matches, String field, String operator, String value) {
        static final SimpleCondition NO_MATCH = new SimpleCondition(false, null, null, null);
    }

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
        JsonCondition condition = cached(jsonConditions, rule.ruleExpression(), this::parseJsonCondition);
        if (!condition.json()) {
            return matchesSimpleExpression(rule.ruleExpression(), transactionData);
        }
        try {
            return evaluateCondition(condition.field(), condition.operator(), condition.value(), transactionData);
        } catch (Exception ex) {
            // Same fallback as before when evaluating a JSON expression failed.
            return matchesSimpleExpression(rule.ruleExpression(), transactionData);
        }
    }

    /** Parses once what was parsed on every call before (same field/operator/value). */
    private JsonCondition parseJsonCondition(String ruleExpression) {
        try {
            Map<String, Object> expression = objectMapper.readValue(ruleExpression, EXPRESSION_TYPE);
            return new JsonCondition(
                    true,
                    Objects.toString(expression.get("field"), ""),
                    Objects.toString(expression.get("operator"), ""),
                    expression.get("value")
            );
        } catch (Exception ex) {
            // Not a JSON object (e.g. "ip == '1.2.3.4'") - before, this threw on every call.
            return JsonCondition.NOT_JSON;
        }
    }

    private boolean matchesSimpleExpression(String ruleExpression, Map<String, Object> transactionData) {
        SimpleCondition condition = cached(simpleConditions, ruleExpression, this::parseSimpleCondition);
        if (!condition.matches()) {
            return false;
        }

        return evaluateCondition(condition.field(), condition.operator(), condition.value(), transactionData);
    }

    private SimpleCondition parseSimpleCondition(String ruleExpression) {
        Matcher matcher = SIMPLE_EXPRESSION.matcher(ruleExpression);
        if (!matcher.matches()) {
            return SimpleCondition.NO_MATCH;
        }
        return new SimpleCondition(true, matcher.group(1), matcher.group(2), stripWrappingQuotes(matcher.group(3)));
    }

    private <T> T cached(Map<String, T> cache, String ruleExpression, java.util.function.Function<String, T> parser) {
        T parsed = cache.get(ruleExpression);
        if (parsed != null) {
            return parsed;
        }
        if (cache.size() >= MAX_PARSED_EXPRESSIONS) {
            cache.clear();
        }
        parsed = parser.apply(ruleExpression);
        cache.put(ruleExpression, parsed);
        return parsed;
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
        String text = value.toString();
        char first = text.charAt(0);
        // new BigDecimal(text) throws for any text that does not start with a digit,
        // '+', '-' or '.', so those are "not a number" without throwing an exception
        // first (text values like cities / IPs / device ids hit this on every rule).
        if (!Character.isDigit(first) && first != '+' && first != '-' && first != '.') {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer safeScore(Integer ruleScore) {
        return Math.max(ruleScore != null ? ruleScore : 0, 0);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String trimmedUpper = value.trim().toUpperCase(Locale.ROOT);
        String commaSpacingFixed = COMMA_SPACING.matcher(trimmedUpper).replaceAll(", ");
        return EXTRA_WHITESPACE.matcher(commaSpacingFixed).replaceAll(" ").trim();
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
