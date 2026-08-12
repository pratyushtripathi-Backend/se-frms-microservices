package com.se.frms.transaction.service;
import com.se.frms.transaction.dto.TransactionRequest;
import com.se.frms.transaction.dto.TransactionResponse;
public interface TransactionService {
    TransactionResponse process(TransactionRequest request);
}
