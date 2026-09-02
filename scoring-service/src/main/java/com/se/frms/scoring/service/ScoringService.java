package com.se.frms.scoring.service;

import com.se.frms.scoring.dto.MatchedRuleHistoryResponse;
import com.se.frms.scoring.dto.ScoringHistoryResponse;
import com.se.frms.scoring.dto.ScoringRequest;
import com.se.frms.scoring.dto.ScoringResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;

public interface ScoringService {

    ScoringResponse process(ScoringRequest request);

    ScoringResponse getByScoringId(UUID scoringId);

    ScoringResponse getLatestByTransactionId(UUID transactionId);

    List<ScoringResponse> getHistoryByTransactionId(UUID transactionId);

    // Full matched-rule history across ALL transactions, for frontend listing/reporting.
    // page/size both null -> returns EVERYTHING (no pagination).
    // size provided -> normal paginated response.
    Page<MatchedRuleHistoryResponse> getAllMatchedRules(Integer page, Integer size);

    // Full scoring history across ALL transactions, for frontend listing/reporting.
    // page/size both null -> returns EVERYTHING (no pagination).
    // size provided -> normal paginated response.
    Page<ScoringHistoryResponse> getAllScorings(Integer page, Integer size);
}
