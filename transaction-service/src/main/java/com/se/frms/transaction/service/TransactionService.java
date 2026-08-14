package com.se.frms.transaction.service;

import com.se.frms.transaction.dto.TransactionDetailsResponse;
import com.se.frms.transaction.dto.TransactionRequest;
import com.se.frms.transaction.dto.TransactionResponse;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface TransactionService {

    TransactionResponse process(TransactionRequest request);

    Page<TransactionDetailsResponse> getAll(
            Pageable pageable,
            String status,
            String merchantId,
            String userId,
            String channel,
            String transactionType,
            String currency,
            LocalDate fromDate,
            LocalDate toDate
    );

    TransactionDetailsResponse getById(UUID transactionId);
}
