package com.se.frms.scoring.client;

/** Best-effort only - never allowed to affect the rule-based scoring response. */
public class AiScoringUnavailableException extends RuntimeException {

    public AiScoringUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
