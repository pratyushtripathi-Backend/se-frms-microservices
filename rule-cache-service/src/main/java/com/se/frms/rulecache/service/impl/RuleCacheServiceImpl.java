package com.se.frms.rulecache.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.se.frms.rulecache.client.MonolithRuleClient;
import com.se.frms.rulecache.dto.ActiveRuleResponse;
import com.se.frms.rulecache.dto.DecisionPolicyCacheResponse;
import com.se.frms.rulecache.dto.DecisionPolicyCacheSyncResponseDTO;
import com.se.frms.rulecache.dto.RuleCacheSyncResponseDTO;
import com.se.frms.rulecache.entity.DecisionPolicyCache;
import com.se.frms.rulecache.entity.RuleCache;
import com.se.frms.rulecache.repository.DecisionPolicyCacheRepository;
import com.se.frms.rulecache.repository.RuleCacheRepository;
import com.se.frms.rulecache.service.RuleCacheService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleCacheServiceImpl implements RuleCacheService {

    private final RuleCacheRepository ruleCacheRepository;

    private final DecisionPolicyCacheRepository decisionPolicyCacheRepository;

    private final MonolithRuleClient monolithRuleClient;

    private final StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper;

    @Value("${rule-cache.redis.active-rules-key:frms:rule-cache:active-rules}")
    private String activeRulesCacheKey;

    @Value("${rule-cache.redis.active-decision-policy-key:frms:rule-cache:active-decision-policy}")
    private String activeDecisionPolicyCacheKey;

    @Value("${rule-cache.redis.ttl-minutes:10}")
    private Long redisTtlMinutes;

    @Override
    @Transactional(readOnly = true)
    public List<ActiveRuleResponse> getActiveRules() {

        List<ActiveRuleResponse> redisRules =
                getActiveRulesFromRedis();

        if (!redisRules.isEmpty()) {

            log.info("Active rules fetched from Redis cache");

            return redisRules;
        }

        List<ActiveRuleResponse> dbRules =
                getActiveRulesFromDatabase();

        saveActiveRulesToRedis(dbRules);

        log.info("Active rules fetched from database and saved to Redis");

        return dbRules;
    }

    @Override
    @Transactional(readOnly = true)
    public DecisionPolicyCacheResponse getActiveDecisionPolicy() {

        DecisionPolicyCacheResponse redisPolicy =
                getActiveDecisionPolicyFromRedis();

        if (redisPolicy != null) {
            log.info("Active decision policy fetched from Redis cache");
            return redisPolicy;
        }

        DecisionPolicyCacheResponse dbPolicy =
                getActiveDecisionPolicyFromDatabase();

        saveActiveDecisionPolicyToRedis(dbPolicy);

        log.info("Active decision policy fetched from database and saved to Redis");

        return dbPolicy;
    }

    @Override
    @Transactional
    public void syncFromMonolith() {

        List<RuleCacheSyncResponseDTO> activeRules =
                monolithRuleClient.fetchActiveRules();

        Set<Integer> activeRuleIds =
                activeRules
                        .stream()
                        .map(RuleCacheSyncResponseDTO::getRuleId)
                        .collect(Collectors.toSet());

        for (RuleCacheSyncResponseDTO activeRule : activeRules) {
            upsertRule(activeRule);
        }

        List<RuleCache> existingActiveRules =
                ruleCacheRepository.findByStatusTrue();

        for (RuleCache existingRule : existingActiveRules) {

            if (!activeRuleIds.contains(existingRule.getRuleId())) {

                existingRule.setStatus(false);

                ruleCacheRepository.save(existingRule);
            }
        }

        List<ActiveRuleResponse> latestActiveRules =
                getActiveRulesFromDatabase();

        saveActiveRulesToRedis(latestActiveRules);

        DecisionPolicyCacheSyncResponseDTO activeDecisionPolicy =
                monolithRuleClient.fetchActiveDecisionPolicy();

        if (activeDecisionPolicy != null) {
            upsertDecisionPolicy(activeDecisionPolicy);
            saveActiveDecisionPolicyToRedis(getActiveDecisionPolicyFromDatabase());
        }

        log.info(
                "Rule cache sync completed, activeRuleCount={}, decisionPolicyId={}",
                activeRules.size(),
                activeDecisionPolicy != null ? activeDecisionPolicy.getPolicyId() : null
        );
    }

    private void upsertRule(
            RuleCacheSyncResponseDTO activeRule
    ) {

        RuleCache ruleCache =
                ruleCacheRepository
                        .findByRuleId(activeRule.getRuleId())
                        .orElseGet(RuleCache::new);

        ruleCache.setRuleId(activeRule.getRuleId());
        ruleCache.setCategoryId(activeRule.getCategoryId());
        ruleCache.setRuleCode(activeRule.getRuleCode());
        ruleCache.setRuleName(activeRule.getRuleName());
        ruleCache.setRuleDescription(
                activeRule.getRuleDescription() == null
                        ? ""
                        : activeRule.getRuleDescription()
        );
        ruleCache.setRuleExpression(activeRule.getRuleExpression());
        ruleCache.setCategoryName(activeRule.getCategoryName());
        ruleCache.setRuleScore(activeRule.getRuleScore());
        ruleCache.setStatus(true);
        ruleCache.setCreatedBy(
                activeRule.getCreatedBy() == null
                        ? "SYSTEM"
                        : activeRule.getCreatedBy()
        );

        ruleCacheRepository.save(ruleCache);
    }

    private void upsertDecisionPolicy(
            DecisionPolicyCacheSyncResponseDTO activeDecisionPolicy
    ) {

        DecisionPolicyCache decisionPolicyCache =
                decisionPolicyCacheRepository
                        .findByPolicyId(activeDecisionPolicy.getPolicyId())
                        .orElseGet(DecisionPolicyCache::new);

        decisionPolicyCache.setPolicyId(activeDecisionPolicy.getPolicyId());
        decisionPolicyCache.setDescription(activeDecisionPolicy.getDescription());
        decisionPolicyCache.setAllowMinScore(activeDecisionPolicy.getAllowMinScore());
        decisionPolicyCache.setAllowMaxScore(activeDecisionPolicy.getAllowMaxScore());
        decisionPolicyCache.setReviewMinScore(activeDecisionPolicy.getReviewMinScore());
        decisionPolicyCache.setReviewMaxScore(activeDecisionPolicy.getReviewMaxScore());
        decisionPolicyCache.setBlockMinScore(activeDecisionPolicy.getBlockMinScore());
        decisionPolicyCache.setBlockMaxScore(activeDecisionPolicy.getBlockMaxScore());
        decisionPolicyCache.setStatus(Boolean.TRUE.equals(activeDecisionPolicy.getStatus()));
        decisionPolicyCache.setCreatedBy(
                activeDecisionPolicy.getCreatedBy() == null
                        ? "SYSTEM"
                        : activeDecisionPolicy.getCreatedBy()
        );
        decisionPolicyCache.setCreatedAt(activeDecisionPolicy.getCreatedAt());
        decisionPolicyCache.setUpdatedAt(activeDecisionPolicy.getUpdatedAt());

        decisionPolicyCacheRepository.save(decisionPolicyCache);
    }

    private List<ActiveRuleResponse> getActiveRulesFromDatabase() {

        return ruleCacheRepository
                .findByStatusTrueOrderByUpdatedAtDesc()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    private List<ActiveRuleResponse> getActiveRulesFromRedis() {

        try {

            String cachedRules =
                    stringRedisTemplate
                            .opsForValue()
                            .get(activeRulesCacheKey);

            if (cachedRules == null || cachedRules.isBlank()) {
                return List.of();
            }

            return objectMapper.readValue(
                    cachedRules,
                    new TypeReference<List<ActiveRuleResponse>>() {
                    }
            );

        } catch (Exception ex) {

            log.warn(
                    "Failed to read active rules from Redis: {}",
                    ex.getMessage()
            );

            return List.of();
        }
    }

    private DecisionPolicyCacheResponse getActiveDecisionPolicyFromDatabase() {

        return decisionPolicyCacheRepository
                .findFirstByStatusTrueOrderByUpdatedAtDesc()
                .map(this::mapToDecisionPolicyResponse)
                .orElse(null);
    }

    private DecisionPolicyCacheResponse getActiveDecisionPolicyFromRedis() {

        try {

            String cachedPolicy =
                    stringRedisTemplate
                            .opsForValue()
                            .get(activeDecisionPolicyCacheKey);

            if (cachedPolicy == null || cachedPolicy.isBlank()) {
                return null;
            }

            return objectMapper.readValue(
                    cachedPolicy,
                    DecisionPolicyCacheResponse.class
            );

        } catch (Exception ex) {

            log.warn(
                    "Failed to read active decision policy from Redis: {}",
                    ex.getMessage()
            );

            return null;
        }
    }

    private void saveActiveRulesToRedis(
            List<ActiveRuleResponse> activeRules
    ) {

        try {

            String rulesJson =
                    objectMapper.writeValueAsString(activeRules);

            stringRedisTemplate
                    .opsForValue()
                    .set(
                            activeRulesCacheKey,
                            rulesJson,
                            Duration.ofMinutes(redisTtlMinutes)
                    );

        } catch (Exception ex) {

            log.warn(
                    "Failed to save active rules to Redis: {}",
                    ex.getMessage()
            );
        }
    }

    private void saveActiveDecisionPolicyToRedis(
            DecisionPolicyCacheResponse activeDecisionPolicy
    ) {

        if (activeDecisionPolicy == null) {
            return;
        }

        try {

            String policyJson =
                    objectMapper.writeValueAsString(activeDecisionPolicy);

            stringRedisTemplate
                    .opsForValue()
                    .set(
                            activeDecisionPolicyCacheKey,
                            policyJson,
                            Duration.ofMinutes(redisTtlMinutes)
                    );

        } catch (Exception ex) {

            log.warn(
                    "Failed to save active decision policy to Redis: {}",
                    ex.getMessage()
            );
        }
    }

    private ActiveRuleResponse mapToResponse(
            RuleCache ruleCache
    ) {

        return new ActiveRuleResponse(
                ruleCache.getId(),
                ruleCache.getRuleId(),
                ruleCache.getCategoryId(),
                ruleCache.getRuleCode(),
                ruleCache.getRuleName(),
                ruleCache.getRuleDescription(),
                ruleCache.getRuleExpression(),
                ruleCache.getCategoryName(),
                ruleCache.getRuleScore(),
                ruleCache.getStatus(),
                ruleCache.getCreatedBy(),
                ruleCache.getCreatedDate(),
                ruleCache.getUpdatedAt()
        );
    }

    private DecisionPolicyCacheResponse mapToDecisionPolicyResponse(
            DecisionPolicyCache decisionPolicyCache
    ) {

        return new DecisionPolicyCacheResponse(
                decisionPolicyCache.getId(),
                decisionPolicyCache.getPolicyId(),
                decisionPolicyCache.getDescription(),
                decisionPolicyCache.getAllowMinScore(),
                decisionPolicyCache.getAllowMaxScore(),
                decisionPolicyCache.getReviewMinScore(),
                decisionPolicyCache.getReviewMaxScore(),
                decisionPolicyCache.getBlockMinScore(),
                decisionPolicyCache.getBlockMaxScore(),
                decisionPolicyCache.getStatus(),
                decisionPolicyCache.getCreatedBy(),
                decisionPolicyCache.getCreatedAt(),
                decisionPolicyCache.getUpdatedAt()
        );
    }
}
