package com.se.frms.scoring.repository;

import com.se.frms.scoring.entity.Scoring;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ScoringRepository extends JpaRepository<Scoring, UUID>, JpaSpecificationExecutor<Scoring> {

    // latest scoring attempt for a transaction (use this for the "GET by transactionId" API)
    Optional<Scoring> findTopByTransactionIdOrderByCreatedDateDesc(UUID transactionId);

    // all scoring attempts for a transaction (use this for the "history" API)
    List<Scoring> findByTransactionIdOrderByCreatedDateDesc(UUID transactionId);

    // Fills in the AI shadow-score once it arrives, well after this row was first persisted.
    // A targeted UPDATE (not findById+save) so it never collides with the batched writer's own
    // insert; returns 0, harmlessly, if the row hasn't landed yet (see AiScoringAsyncService).
    // @Transactional here (not only on the caller): AiScoringAsyncService calls its own
    // updateRow() internally, which bypasses Spring's proxy, so a @Modifying query had no
    // transaction and failed with "Executing an update/delete query".
    @Modifying
    @Transactional
    @Query("UPDATE Scoring s SET s.aiRiskScore = :aiRiskScore, s.aiReasoning = :aiReasoning, "
            + "s.updatedAt = CURRENT_TIMESTAMP WHERE s.id = :scoringId")
    int updateAiRiskScore(
            @Param("scoringId") UUID scoringId,
            @Param("aiRiskScore") Double aiRiskScore,
            @Param("aiReasoning") String aiReasoning
    );
}