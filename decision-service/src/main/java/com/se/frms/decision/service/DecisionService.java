package com.se.frms.decision.service;
import com.se.frms.decision.dto.CaseResponse;
import com.se.frms.decision.dto.DecisionRequest;
import com.se.frms.decision.dto.DecisionResponse;
import com.se.frms.decision.dto.DecisionReviewRequest;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface DecisionService {

    DecisionResponse process(DecisionRequest request);

    Page<DecisionResponse> getAll(Integer year, LocalDate startDate, LocalDate endDate, Pageable pageable);

    DecisionResponse getById(UUID decisionId);

    DecisionResponse getByTransactionId(UUID transactionId);

    /** Case-management list: decisions matching {@code status} (defaults to REVIEW), enriched with amount/mode and matched rules. */
    Page<CaseResponse> getCases(String status, Pageable pageable);

    /** Admin allow/block action: overwrites finalDecision only, on an existing decision. */
    DecisionResponse reviewDecision(UUID decisionId, DecisionReviewRequest request);
}
