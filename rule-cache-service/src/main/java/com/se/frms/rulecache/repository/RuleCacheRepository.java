package com.se.frms.rulecache.repository;

import com.se.frms.rulecache.entity.RuleCache;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleCacheRepository extends JpaRepository<RuleCache, UUID> {
}
