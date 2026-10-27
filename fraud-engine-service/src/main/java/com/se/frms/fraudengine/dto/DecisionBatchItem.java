package com.se.frms.fraudengine.dto;

import java.util.UUID;

/** One result of Decision Service's POST /api/v1/decisions/batch. */
public record DecisionBatchItem(UUID transactionId, int status, DecisionResponse response, String error) {
}
