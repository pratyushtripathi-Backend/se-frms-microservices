package com.se.frms.fraudengine.cache;

import com.se.frms.fraudengine.client.RuleCacheClient;
import com.se.frms.fraudengine.dto.ActiveRuleResponse;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ActiveRuleCache {

    private final RuleCacheClient ruleCacheClient;
    private volatile List<ActiveRuleResponse> activeRules = List.of();

    @PostConstruct
    public void warmUp() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${frms.rule-cache.refresh-interval-ms:30000}")
    public void refresh() {
        try {
            List<ActiveRuleResponse> refreshedRules = ruleCacheClient.getActiveRules();
            activeRules = List.copyOf(refreshedRules);
            log.info("Active rule cache refreshed ruleCount={}", activeRules.size());
        } catch (RuntimeException ex) {
            log.warn(
                    "Active rule cache refresh failed. Keeping existing ruleCount={}, reason={}",
                    activeRules.size(),
                    ex.getMessage()
            );
        }
    }

    public List<ActiveRuleResponse> getActiveRules() {
        return activeRules;
    }
}
