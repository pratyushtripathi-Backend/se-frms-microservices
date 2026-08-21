package com.se.frms.scoring.repository;

import com.se.frms.scoring.entity.Scoring;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScoringRepository extends JpaRepository<Scoring, UUID> {

    // latest scoring attempt for a transaction (use this for the "GET by transactionId" API)
    Optional<Scoring> findTopByTransactionIdOrderByCreatedDateDesc(UUID transactionId);

    // all scoring attempts for a transaction (use this for the "history" API)
    List<Scoring> findByTransactionIdOrderByCreatedDateDesc(UUID transactionId);
}