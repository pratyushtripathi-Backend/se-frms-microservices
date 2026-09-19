package com.se.frms.scoring.service;

import com.se.frms.scoring.dto.MatchedRuleHistoryResponse;
import com.se.frms.scoring.dto.ScoringHistoryResponse;
import com.se.frms.scoring.dto.ScoringRequest;
import com.se.frms.scoring.dto.ScoringResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ScoringService {

    ScoringResponse process(ScoringRequest request);

    ScoringResponse getByScoringId(UUID scoringId);

    ScoringResponse getLatestByTransactionId(UUID transactionId);

    List<ScoringResponse> getHistoryByTransactionId(UUID transactionId);

    // Full matched-rule history across ALL transactions, for frontend listing/reporting.
    // year/startDate/endDate are optional filters on createdDate.
    Page<MatchedRuleHistoryResponse> getAllMatchedRules(Integer year, LocalDate startDate, LocalDate endDate, Pageable pageable);

    // Full scoring history across ALL transactions, for frontend listing/reporting.
    // year/startDate/endDate are optional filters on createdDate.
    Page<ScoringHistoryResponse> getAllScorings(Integer year, LocalDate startDate, LocalDate endDate, Pageable pageable);
}
