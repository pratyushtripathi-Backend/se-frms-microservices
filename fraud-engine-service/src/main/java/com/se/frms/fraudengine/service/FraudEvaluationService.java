package com.se.frms.fraudengine.service;

import com.se.frms.fraudengine.dto.FraudEvaluationBatchItem;
import com.se.frms.fraudengine.dto.FraudEvaluationRequest;
import com.se.frms.fraudengine.dto.FraudEvaluationResponse;
import java.util.List;

public interface FraudEvaluationService {

    FraudEvaluationResponse evaluate(FraudEvaluationRequest request);

    /** Same evaluation for several transactions; one result per request, same order. */
    List<FraudEvaluationBatchItem> evaluateBatch(List<FraudEvaluationRequest> requests);
}
