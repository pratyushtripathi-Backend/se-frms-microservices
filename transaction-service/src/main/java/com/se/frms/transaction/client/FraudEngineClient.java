package com.se.frms.transaction.client;
import com.se.frms.transaction.dto.FraudEvaluationRequest;
import com.se.frms.transaction.dto.FraudEvaluationResponse;
import org.springframework.stereotype.Component;
@Component
public class FraudEngineClient {
    public FraudEvaluationResponse evaluate(FraudEvaluationRequest request) {
        throw new UnsupportedOperationException("Fraud Engine REST call skeleton only.");
    }
}
