package com.se.frms.ai.dto;

import java.util.Map;

/** {@code rule} is expected to have exactly the keys "field", "operator", "value". */
public record NlToRuleResponse(Map<String, Object> rule) {
}
