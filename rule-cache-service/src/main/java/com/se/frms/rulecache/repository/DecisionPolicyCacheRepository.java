package com.se.frms.rulecache.repository;

import com.se.frms.rulecache.entity.DecisionPolicyCache;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionPolicyCacheRepository extends JpaRepository<DecisionPolicyCache, UUID> {

    Optional<DecisionPolicyCache> findByPolicyId(Integer policyId);

    Optional<DecisionPolicyCache> findFirstByStatusTrueOrderByUpdatedAtDesc();
}
