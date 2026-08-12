package com.se.frms.fraudengine.service;
import com.se.frms.fraudengine.dto.FraudEvaluationRequest;
import com.se.frms.fraudengine.dto.FraudEvaluationResponse;
public interface FraudEvaluationService { FraudEvaluationResponse evaluate(FraudEvaluationRequest request); }
