package com.se.frms.transaction.dto;
import jakarta.validation.constraints.NotEmpty;
import java.util.Map;
public record TransactionRequest(@NotEmpty Map<String, Object> transactionData, String createdBy) {}
