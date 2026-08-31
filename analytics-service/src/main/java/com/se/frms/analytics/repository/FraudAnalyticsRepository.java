package com.se.frms.analytics.repository;

import com.se.frms.analytics.entity.FraudAnalytics;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FraudAnalyticsRepository extends JpaRepository<FraudAnalytics, UUID>, JpaSpecificationExecutor<FraudAnalytics> {
    Optional<FraudAnalytics> findByTransactionId(UUID transactionId);

    long countByFraudDecisionAndCreatedAtBetween(String fraudDecision, LocalDateTime fromDate, LocalDateTime toDate);

    Page<FraudAnalytics> findByFraudDecisionAndCreatedAtBetween(
            String fraudDecision,
            LocalDateTime fromDate,
            LocalDateTime toDate,
            Pageable pageable
    );
}
