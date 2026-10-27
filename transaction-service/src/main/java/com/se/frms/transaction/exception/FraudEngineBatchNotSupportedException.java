package com.se.frms.transaction.exception;

/** fraud-engine has no batch endpoint (older version): evaluate one by one instead. */
public class FraudEngineBatchNotSupportedException extends RuntimeException {

    public FraudEngineBatchNotSupportedException(Throwable cause) {
        super("fraud-engine has no batch endpoint", cause);
    }
}
