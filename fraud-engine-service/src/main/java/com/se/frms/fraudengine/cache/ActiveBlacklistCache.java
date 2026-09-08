package com.se.frms.fraudengine.cache;

import com.se.frms.fraudengine.client.BlacklistCacheClient;
import com.se.frms.fraudengine.dto.ActiveBlacklistResponse;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ActiveBlacklistCache {

    private final BlacklistCacheClient blacklistCacheClient;
    private volatile List<ActiveBlacklistResponse> activeBlacklist = List.of();

    @PostConstruct
    public void warmUp() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${frms.rule-cache.refresh-interval-ms:30000}")
    public void refresh() {
        try {
            List<ActiveBlacklistResponse> refreshedBlacklist = blacklistCacheClient.getActiveBlacklist();
            activeBlacklist = List.copyOf(refreshedBlacklist);
            log.info("Active blacklist cache refreshed entryCount={}", activeBlacklist.size());
        } catch (RuntimeException ex) {
            log.warn(
                    "Active blacklist cache refresh failed. Keeping existing entryCount={}, reason={}",
                    activeBlacklist.size(),
                    ex.getMessage()
            );
        }
    }

    public List<ActiveBlacklistResponse> getActiveBlacklist() {
        return activeBlacklist;
    }
}
