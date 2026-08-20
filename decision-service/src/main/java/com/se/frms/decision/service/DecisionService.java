package com.se.frms.decision.service;
import com.se.frms.decision.dto.DecisionRequest;
import com.se.frms.decision.dto.DecisionResponse;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface DecisionService {

    DecisionResponse process(DecisionRequest request);

    Page<DecisionResponse> getAll(Pageable pageable);

    DecisionResponse getById(UUID decisionId);

    DecisionResponse getByTransactionId(UUID transactionId);
}
