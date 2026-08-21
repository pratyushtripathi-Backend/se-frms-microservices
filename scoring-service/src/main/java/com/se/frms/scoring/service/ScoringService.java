package com.se.frms.scoring.service;

import com.se.frms.scoring.dto.ScoringRequest;
import com.se.frms.scoring.dto.ScoringResponse;
import java.util.List;
import java.util.UUID;

public interface ScoringService {

    ScoringResponse process(ScoringRequest request);

    ScoringResponse getByScoringId(UUID scoringId);

    ScoringResponse getLatestByTransactionId(UUID transactionId);

    List<ScoringResponse> getHistoryByTransactionId(UUID transactionId);
}