package com.se.frms.ai.exception;

/** Thrown when a gated endpoint (risk-score, chat) is called while its feature flag is off. */
public class FeatureDisabledException extends RuntimeException {

    public FeatureDisabledException(String message) {
        super(message);
    }
}
