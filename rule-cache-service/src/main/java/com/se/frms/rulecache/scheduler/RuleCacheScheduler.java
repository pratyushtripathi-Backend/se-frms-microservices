package com.se.frms.rulecache.scheduler;

import com.se.frms.rulecache.service.RuleCacheService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RuleCacheScheduler {

    private final RuleCacheService ruleCacheService;

    @Scheduled(
            initialDelayString = "${rule-cache.sync.initial-delay-ms:30000}",
            fixedDelayString = "${rule-cache.sync.fixed-delay-ms:300000}"
    )
    public void syncRuleCache() {

        try {

            ruleCacheService.syncFromMonolith();

        } catch (Exception ex) {

            log.warn(
                    "Rule cache scheduled sync failed: {}",
                    ex.getMessage()
            );
        }
    }
}