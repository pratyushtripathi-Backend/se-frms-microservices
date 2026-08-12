package com.se.frms.fraudengine.service.impl;
import com.se.frms.fraudengine.dto.FraudEvaluationRequest;
import com.se.frms.fraudengine.dto.FraudEvaluationResponse;
import com.se.frms.fraudengine.service.FraudEvaluationService;
import org.springframework.stereotype.Service;
@Service
public class FraudEvaluationServiceImpl implements FraudEvaluationService {
    @Override
    public FraudEvaluationResponse evaluate(FraudEvaluationRequest request) {
        throw new UnsupportedOperationException("Fraud orchestration skeleton only.");
    }
}
