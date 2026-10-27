package com.se.frms.fraudengine.client;

/**
 * Outcome of one transaction in a batch call to Scoring / Decision Service: either the
 * same response the single call returns, or the same exception it throws.
 */
public record BatchOutcome<T>(T value, RuntimeException error) {

    public static <T> BatchOutcome<T> ok(T value) {
        return new BatchOutcome<>(value, null);
    }

    public static <T> BatchOutcome<T> failed(RuntimeException error) {
        return new BatchOutcome<>(null, error);
    }
}
