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

    // Three fixed, hardcoded queries - one per granularity - rather than
    // building the GROUP BY/date_trunc argument dynamically from the
    // caller-supplied groupBy value. The service layer validates groupBy
    // against a fixed allow-list and picks one of these methods; the SQL
    // text itself never has a request-controlled value interpolated into it.
    // "Fraud Alert" mirrors getSummary(): everything not cleanly ALLOW-ed
    // (REVIEW + BLOCK). "Blocked" is fraud_decision = 'BLOCK'.
    @Query(
            value = "SELECT TO_CHAR(date_trunc('month', created_at), 'YYYY-MM') AS period, "
                    + "COUNT(*) FILTER (WHERE fraud_decision IN ('REVIEW', 'BLOCK')) AS fraudAlertCount, "
                    + "COUNT(*) FILTER (WHERE fraud_decision = 'BLOCK') AS blockedCount "
                    + "FROM se_frms_fraud_analytics "
                    + "WHERE created_at BETWEEN :fromDate AND :toDate "
                    + "GROUP BY date_trunc('month', created_at) "
                    + "ORDER BY date_trunc('month', created_at)",
            nativeQuery = true
    )
    List<TrendProjection> getFraudTrendByMonth(@Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

    @Query(
            value = "SELECT TO_CHAR(date_trunc('day', created_at), 'YYYY-MM-DD') AS period, "
                    + "COUNT(*) FILTER (WHERE fraud_decision IN ('REVIEW', 'BLOCK')) AS fraudAlertCount, "
                    + "COUNT(*) FILTER (WHERE fraud_decision = 'BLOCK') AS blockedCount "
                    + "FROM se_frms_fraud_analytics "
                    + "WHERE created_at BETWEEN :fromDate AND :toDate "
                    + "GROUP BY date_trunc('day', created_at) "
                    + "ORDER BY date_trunc('day', created_at)",
            nativeQuery = true
    )
    List<TrendProjection> getFraudTrendByDay(@Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

    // ISO week: date_trunc('week', ...) truncates to that week's Monday, and
    // 'IYYY-IW' formats it as ISO year + ISO week number (e.g. "2026-W37"),
    // avoiding the ambiguity of plain calendar week numbering across year
    // boundaries.
    @Query(
            value = "SELECT TO_CHAR(date_trunc('week', created_at), 'IYYY-\"W\"IW') AS period, "
                    + "COUNT(*) FILTER (WHERE fraud_decision IN ('REVIEW', 'BLOCK')) AS fraudAlertCount, "
                    + "COUNT(*) FILTER (WHERE fraud_decision = 'BLOCK') AS blockedCount "
                    + "FROM se_frms_fraud_analytics "
                    + "WHERE created_at BETWEEN :fromDate AND :toDate "
                    + "GROUP BY date_trunc('week', created_at) "
                    + "ORDER BY date_trunc('week', created_at)",
            nativeQuery = true
    )
    List<TrendProjection> getFraudTrendByWeek(@Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

    @Query(
            value = "SELECT TO_CHAR(date_trunc('quarter', created_at), 'YYYY') || '-Q' "
                    + "|| TO_CHAR(date_trunc('quarter', created_at), 'Q') AS period, "
                    + "COUNT(*) FILTER (WHERE fraud_decision IN ('REVIEW', 'BLOCK')) AS fraudAlertCount, "
                    + "COUNT(*) FILTER (WHERE fraud_decision = 'BLOCK') AS blockedCount "
                    + "FROM se_frms_fraud_analytics "
                    + "WHERE created_at BETWEEN :fromDate AND :toDate "
                    + "GROUP BY date_trunc('quarter', created_at) "
                    + "ORDER BY date_trunc('quarter', created_at)",
            nativeQuery = true
    )
    List<TrendProjection> getFraudTrendByQuarter(@Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

    @Query(
            value = "SELECT TO_CHAR(date_trunc('year', created_at), 'YYYY') AS period, "
                    + "COUNT(*) FILTER (WHERE fraud_decision IN ('REVIEW', 'BLOCK')) AS fraudAlertCount, "
                    + "COUNT(*) FILTER (WHERE fraud_decision = 'BLOCK') AS blockedCount "
                    + "FROM se_frms_fraud_analytics "
                    + "WHERE created_at BETWEEN :fromDate AND :toDate "
                    + "GROUP BY date_trunc('year', created_at) "
                    + "ORDER BY date_trunc('year', created_at)",
            nativeQuery = true
    )
    List<TrendProjection> getFraudTrendByYear(@Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

    interface TrendProjection {
        String getPeriod();
        Long getFraudAlertCount();
        Long getBlockedCount();
    }
}
