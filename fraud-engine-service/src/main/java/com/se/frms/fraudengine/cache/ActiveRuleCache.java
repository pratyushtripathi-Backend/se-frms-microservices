package com.se.frms.fraudengine.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.se.frms.fraudengine.client.RuleCacheClient;
import com.se.frms.fraudengine.dto.ActiveRuleResponse;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ActiveRuleCache {

    /**
     * The active rules and a fingerprint of their exact content (SHA-256 of the rules
     * as JSON). The fingerprint changes whenever any rule field changes, so Scoring
     * Service can safely re-use a rule list it already received for the same version
     * (see ScoringClient) instead of receiving it again with every transaction.
     */
    public record Snapshot(List<ActiveRuleResponse> rules, String version) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RuleCacheClient ruleCacheClient;
    private volatile Snapshot snapshot = new Snapshot(List.of(), null);

    @PostConstruct
    public void warmUp() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${frms.rule-cache.refresh-interval-ms:30000}")
    public void refresh() {
        try {
            List<ActiveRuleResponse> refreshedRules = List.copyOf(ruleCacheClient.getActiveRules());
            Snapshot current = snapshot;
            if (!refreshedRules.equals(current.rules()) || current.version() == null) {
                snapshot = new Snapshot(refreshedRules, versionOf(refreshedRules));
            }
            log.info("Active rule cache refreshed ruleCount={}", refreshedRules.size());
        } catch (RuntimeException ex) {
            log.warn(
                    "Active rule cache refresh failed. Keeping existing ruleCount={}, reason={}",
                    snapshot.rules().size(),
                    ex.getMessage()
            );
        }
    }

    public List<ActiveRuleResponse> getActiveRules() {
        return snapshot.rules();
    }

    /** Rules and their version, read together (always consistent with each other). */
    public Snapshot getSnapshot() {
        return snapshot;
    }

    private static String versionOf(List<ActiveRuleResponse> rules) {
        try {
            byte[] json = JSON.writeValueAsString(rules).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (Exception ex) {
            // No version: Scoring Service simply receives the full rule list, as before.
            log.warn("Could not compute active rules version, reason={}", ex.getMessage());
            return null;
        }
    }
}
