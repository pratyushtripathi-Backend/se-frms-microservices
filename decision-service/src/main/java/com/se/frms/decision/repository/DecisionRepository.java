package com.se.frms.decision.repository;

import com.se.frms.decision.entity.Decision;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionRepository extends JpaRepository<Decision, UUID> {

    Optional<Decision> findByTransactionId(UUID transactionId);

    Page<Decision> findByFinalDecision(String finalDecision, Pageable pageable);
}
