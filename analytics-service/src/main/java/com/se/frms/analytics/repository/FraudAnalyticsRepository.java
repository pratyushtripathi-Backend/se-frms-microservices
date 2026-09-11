package com.se.frms.analytics.repository;

import com.se.frms.analytics.entity.FraudAnalytics;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FraudAnalyticsRepository extends JpaRepository<FraudAnalytics, UUID>, JpaSpecificationExecutor<FraudAnalytics> {
    Optional<FraudAnalytics> findByTransactionId(UUID transactionId);

    long countByFraudDecisionAndCreatedAtBetween(String fraudDecision, LocalDateTime fromDate, LocalDateTime toDate);

    Page<FraudAnalytics> findByFraudDecisionAndCreatedAtBetween(
            String fraudDecision,
            LocalDateTime fromDate,
            LocalDateTime toDate,
            Pageable pageable
    );

    // Native query: needs a date-truncation function (CAST ... AS date) that
    // isn't portable via a derived query method or plain JPQL. COALESCE keeps
    // a day with only null-amount rows (pre-migration data) out of the null
    // bucket, returning 0 instead so the chart doesn't render a gap as a crash.
    @Query(
            value = "SELECT CAST(created_at AS date) AS day, COALESCE(SUM(amount), 0) AS totalAmount "
                    + "FROM se_frms_fraud_analytics "
                    + "WHERE created_at BETWEEN :fromDate AND :toDate "
                    + "GROUP BY CAST(created_at AS date) "
                    + "ORDER BY day",
            nativeQuery = true
    )
    List<DailyAmountProjection> sumAmountByDay(@Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

    // channel is a plain column, so this stays portable JPQL rather than native SQL.
    @Query(
            "SELECT f.channel AS channel, COUNT(f) AS transactionCount "
                    + "FROM FraudAnalytics f "
                    + "WHERE f.createdAt BETWEEN :fromDate AND :toDate "
                    + "GROUP BY f.channel"
    )
    List<ChannelCountProjection> countByChannel(@Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

    interface DailyAmountProjection {
        java.sql.Date getDay();
        java.math.BigDecimal getTotalAmount();
    }

    interface ChannelCountProjection {
        String getChannel();
        Long getTransactionCount();
    }
}
