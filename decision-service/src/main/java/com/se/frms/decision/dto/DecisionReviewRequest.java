package com.se.frms.decision.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body for PATCH /api/v1/decisions/{decisionId}/review - the admin
 * allow/block action on the case-management page. finalDecision must be
 * ALLOW or BLOCK (validated in the service layer); remarks is optional and,
 * if given, replaces the stored decisionReason so the override reason is
 * visible on the record.
 */
public record DecisionReviewRequest(
        @NotBlank(message = "finalDecision is required")
        String finalDecision,
        String remarks
) {
}
