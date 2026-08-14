package com.se.frms.rulecache.service;

import com.se.frms.rulecache.dto.ActiveRuleResponse;

import java.util.List;

public interface RuleCacheService {

    List<ActiveRuleResponse> getActiveRules();

    void syncFromMonolith();
}