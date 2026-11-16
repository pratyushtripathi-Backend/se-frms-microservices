package com.se.frms.ai.util;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Strips the obvious PII shapes (email addresses, long digit runs such as
 * account/card numbers) out of any text before it leaves this service for
 * the Claude API, and before it's persisted to the audit log - the
 * proposal's stated mitigation ("redaction step built into ai-service
 * itself... before any external API call"). Not a full PII-detection
 * system, just the two shapes explicitly called out in the proposal.
 */
@Component
public class PiiRedactor {

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\b\\d{9,}\\b");

    public String redact(String text) {
        if (text == null) {
            return null;
        }
        String redacted = EMAIL.matcher(text).replaceAll("[REDACTED_EMAIL]");
        return LONG_DIGIT_RUN.matcher(redacted).replaceAll("[REDACTED_NUMBER]");
    }
}
