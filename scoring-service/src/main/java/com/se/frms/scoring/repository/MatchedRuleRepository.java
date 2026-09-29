package com.se.frms.scoring.repository;

import com.se.frms.scoring.entity.MatchedRule;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface MatchedRuleRepository extends JpaRepository<MatchedRule, UUID>, JpaSpecificationExecutor<MatchedRule> {

    Optional<MatchedRule> findByScoring_Id(UUID scoringId);
}
