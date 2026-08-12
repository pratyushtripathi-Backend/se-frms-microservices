package com.se.frms.decision.service;
import com.se.frms.decision.dto.DecisionRequest;
import com.se.frms.decision.dto.DecisionResponse;
public interface DecisionService { DecisionResponse process(DecisionRequest request); }
