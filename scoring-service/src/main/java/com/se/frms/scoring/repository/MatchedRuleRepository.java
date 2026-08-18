package com.se.frms.scoring.repository;

import com.se.frms.scoring.entity.MatchedRule;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchedRuleRepository extends JpaRepository<MatchedRule, UUID> {
}
