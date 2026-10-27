package com.se.frms.fraudengine.dto;

import java.util.UUID;

/** One result of Scoring Service's POST /api/v1/scoring/batch. */
public record ScoringBatchItem(UUID transactionId, int status, ScoringResponse response, String error) {
}
