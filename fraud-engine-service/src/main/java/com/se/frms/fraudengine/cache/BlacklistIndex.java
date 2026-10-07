package com.se.frms.fraudengine.cache;

import com.se.frms.fraudengine.dto.ActiveBlacklistResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Immutable lookup index over the active blacklist, built once per cache refresh.
 *
 * Replaces the old "one virtual rule per blacklist entry, evaluated on every
 * transaction" approach: a transaction's IP / device / resolved-location value is
 * normalized once and looked up in a hash map, so the cost per transaction no
 * longer grows with the number of blacklist entries.
 *
 * Matching semantics deliberately mirror scoring-service's RuleEvaluator for the
 * old "field == 'value'" virtual rules, so results are identical:
 *  - non-numeric values compare by normalize() (trim, upper-case, comma/space
 *    tidy-up) equality;
 *  - if BOTH sides are numeric they compare numerically (BigDecimal.compareTo),
 *    and are never compared as text;
 *  - every matching entry is returned (duplicates are NOT collapsed), in the
 *    original blacklist order, so scoring and matched-rule output stay the same.
 */
public final class BlacklistIndex {

    public static final BlacklistIndex EMPTY = new BlacklistIndex(Map.of(), Map.of());

    private static final Pattern COMMA_SPACING = Pattern.compile("\\s*,\\s*");
    private static final Pattern EXTRA_WHITESPACE = Pattern.compile("\\s+");

    /** A blacklist entry plus its position in the original list (to preserve ordering). */
    public record IndexedEntry(int order, ActiveBlacklistResponse entry) {
    }

    // type (upper-case) -> normalized text value -> entries
    private final Map<String, Map<String, List<IndexedEntry>>> textIndex;
    // type (upper-case) -> numeric value (trailing zeros stripped) -> entries
    private final Map<String, Map<BigDecimal, List<IndexedEntry>>> numericIndex;

    private BlacklistIndex(
            Map<String, Map<String, List<IndexedEntry>>> textIndex,
            Map<String, Map<BigDecimal, List<IndexedEntry>>> numericIndex
    ) {
        this.textIndex = textIndex;
        this.numericIndex = numericIndex;
    }

    public static BlacklistIndex build(List<ActiveBlacklistResponse> entries) {
        Map<String, Map<String, List<IndexedEntry>>> text = new HashMap<>();
        Map<String, Map<BigDecimal, List<IndexedEntry>>> numeric = new HashMap<>();

        int order = 0;
        for (ActiveBlacklistResponse entry : entries) {
            int position = order++;
            if (entry == null || entry.type() == null || entry.value() == null
                    || entry.value().isBlank() || hasLineTerminator(entry.value())) {
                // Same entries the old virtual-rule path could never match.
                continue;
            }
            String type = entry.type().toUpperCase(Locale.ROOT);
            IndexedEntry indexed = new IndexedEntry(position, entry);

            BigDecimal number = toBigDecimal(entry.value());
            if (number != null) {
                numeric.computeIfAbsent(type, k -> new HashMap<>())
                        .computeIfAbsent(number.stripTrailingZeros(), k -> new ArrayList<>())
                        .add(indexed);
            } else {
                text.computeIfAbsent(type, k -> new HashMap<>())
                        .computeIfAbsent(normalize(entry.value()), k -> new ArrayList<>())
                        .add(indexed);
            }
        }
        return new BlacklistIndex(text, numeric);
    }

    /** All entries of the given type that match the transaction's actual value, in blacklist order. */
    public List<IndexedEntry> match(String type, Object actualValue) {
        if (type == null || actualValue == null) {
            return List.of();
        }
        String upperType = type.toUpperCase(Locale.ROOT);

        BigDecimal actualNumber = toBigDecimal(actualValue);
        if (actualNumber != null) {
            Map<BigDecimal, List<IndexedEntry>> byNumber = numericIndex.get(upperType);
            if (byNumber == null) {
                return List.of();
            }
            List<IndexedEntry> hit = byNumber.get(actualNumber.stripTrailingZeros());
            return hit == null ? List.of() : hit;
        }

        Map<String, List<IndexedEntry>> byText = textIndex.get(upperType);
        if (byText == null) {
            return List.of();
        }
        List<IndexedEntry> hit = byText.get(normalize(actualValue.toString()));
        return hit == null ? List.of() : hit;
    }

    // --- mirrors RuleEvaluator.normalize / toBigDecimal exactly ---

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String trimmedUpper = value.trim().toUpperCase(Locale.ROOT);
        String commaSpacingFixed = COMMA_SPACING.matcher(trimmedUpper).replaceAll(", ");
        return EXTRA_WHITESPACE.matcher(commaSpacingFixed).replaceAll(" ").trim();
    }

    static BigDecimal toBigDecimal(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            if (value instanceof Number number) {
                return BigDecimal.valueOf(number.doubleValue());
            }
            return new BigDecimal(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // A regex '.' does not match these, so the old "field == 'value'" expression
    // could never match an entry containing one.
    private static boolean hasLineTerminator(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0x0A || c == 0x0D || c == 0x85 || c == 0x2028 || c == 0x2029) {
                return true;
            }
        }
        return false;
    }
}
