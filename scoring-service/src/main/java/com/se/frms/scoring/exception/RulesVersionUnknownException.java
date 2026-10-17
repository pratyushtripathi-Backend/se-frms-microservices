package com.se.frms.scoring.exception;

/** A compact scoring request referred to a rules version this instance does not have. */
public class RulesVersionUnknownException extends RuntimeException {

    public RulesVersionUnknownException(String version) {
        super("Rules version not available: " + version);
    }
}
