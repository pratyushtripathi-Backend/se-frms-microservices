package com.se.frms.decision.dto;

import java.util.UUID;

/**
 * Result of one transaction in POST /api/v1/decisions/batch, in the same position as its
 * request. status/error are what POST /api/v1/decisions would have answered for that
 * transaction alone (200 with response, or 400 / 500 with the same error text).
 */
public record DecisionBatchItem(UUID transactionId, int status, DecisionResponse response, String error) {
}
