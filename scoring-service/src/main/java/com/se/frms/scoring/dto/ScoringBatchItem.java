package com.se.frms.scoring.dto;

import java.util.UUID;

/**
 * Result of one transaction in POST /api/v1/scoring/batch, in the same position as its
 * request. status/error are what POST /api/v1/scoring would have answered for that
 * transaction alone (200 with response, or 400 / 409 / 500 with the same error text).
 */
public record ScoringBatchItem(UUID transactionId, int status, ScoringResponse response, String error) {
}
