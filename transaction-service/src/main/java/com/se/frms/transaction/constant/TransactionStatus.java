package com.se.frms.transaction.constant;

public enum TransactionStatus {
    RECEIVED,
    FRAUD_EVALUATION_IN_PROGRESS,
    ALLOW,
    REVIEW,
    BLOCK,
    DUPLICATE_FRAUD,
    FRAUD_ENGINE_FAILED
}

