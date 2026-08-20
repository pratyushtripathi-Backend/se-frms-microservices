package com.se.frms.rulecache.service;

import com.se.frms.rulecache.dto.ActiveRuleResponse;
import com.se.frms.rulecache.dto.DecisionPolicyCacheResponse;

import java.util.List;

public interface RuleCacheService {

    List<ActiveRuleResponse> getActiveRules();

    DecisionPolicyCacheResponse getActiveDecisionPolicy();

    void syncFromMonolith();
}
