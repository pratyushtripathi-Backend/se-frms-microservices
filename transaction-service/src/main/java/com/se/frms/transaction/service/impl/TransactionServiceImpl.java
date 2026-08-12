package com.se.frms.transaction.service.impl;
import com.se.frms.transaction.dto.TransactionRequest;
import com.se.frms.transaction.dto.TransactionResponse;
import com.se.frms.transaction.repository.TransactionRepository;
import com.se.frms.transaction.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service
@RequiredArgsConstructor
public class TransactionServiceImpl implements TransactionService {
    private final TransactionRepository transactionRepository;
    @Override
    public TransactionResponse process(TransactionRequest request) {
        throw new UnsupportedOperationException("Transaction processing skeleton only.");
    }
}
