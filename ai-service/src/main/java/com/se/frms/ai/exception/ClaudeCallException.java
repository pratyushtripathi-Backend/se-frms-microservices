package com.se.frms.ai.exception;

/** Mirrors decision-service's ExternalServiceException shape - thrown whenever the Claude API call fails. */
public class ClaudeCallException extends RuntimeException {

    public ClaudeCallException(String message, Throwable cause) {
        super(message, cause);
    }

    public ClaudeCallException(String message) {
        super(message);
    }
}
