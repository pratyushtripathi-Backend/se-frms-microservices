package com.se.frms.rulecache.service.impl;
import com.se.frms.rulecache.dto.ActiveRuleResponse;
import com.se.frms.rulecache.service.RuleCacheService;
import java.util.List;
import org.springframework.stereotype.Service;
@Service
public class RuleCacheServiceImpl implements RuleCacheService {
    @Override
    public List<ActiveRuleResponse> getActiveRules() {
        throw new UnsupportedOperationException("Rule cache retrieval skeleton only.");
    }
    @Override
    public void syncFromMonolith() {
        throw new UnsupportedOperationException("Rule cache sync skeleton only.");
    }
}
