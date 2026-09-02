package com.se.frms.scoring.repository;

import com.se.frms.scoring.entity.MatchedRule;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchedRuleRepository extends JpaRepository<MatchedRule, UUID> {

    // "Scoring_Id" (underscore) tells Spring Data to traverse the
    // "scoring" ManyToOne relation and match its "id" field
    List<MatchedRule> findByScoring_Id(UUID scoringId);

    // Full matched-rule history across ALL transactions, paginated, newest first.
    // Used by the frontend "all matched rules" table.
    Page<MatchedRule> findAllByOrderByCreatedDateDesc(Pageable pageable);
}
