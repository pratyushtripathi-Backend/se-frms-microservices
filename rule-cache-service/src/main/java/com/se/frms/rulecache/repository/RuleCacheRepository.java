package com.se.frms.rulecache.repository;

import com.se.frms.rulecache.entity.RuleCache;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RuleCacheRepository extends JpaRepository<RuleCache, UUID> {

    Optional<RuleCache> findByRuleId(Integer ruleId);

    List<RuleCache> findByStatusTrue();

    List<RuleCache> findByStatusTrueOrderByUpdatedAtDesc();
}