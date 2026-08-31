package com.se.frms.analytics.service;
import com.se.frms.analytics.dto.AnalyticsSummaryResponse;
import com.se.frms.analytics.dto.DecisionCountResponse;
import com.se.frms.analytics.dto.FraudEvent;
import com.se.frms.analytics.dto.FraudAnalyticsResponse;
import com.se.frms.analytics.dto.RulePerformanceResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AnalyticsService {
    void handleFraudEvent(FraudEvent event);

    Page<FraudAnalyticsResponse> getAll(Pageable pageable, String fraudDecision, LocalDate fromDate, LocalDate toDate);

    FraudAnalyticsResponse getById(UUID analyticsId);

    FraudAnalyticsResponse getByTransactionId(UUID transactionId);

    AnalyticsSummaryResponse getSummary(LocalDate fromDate, LocalDate toDate);

    List<DecisionCountResponse> getDecisionCounts(LocalDate fromDate, LocalDate toDate);

    List<RulePerformanceResponse> getRulePerformance(int limit);
}
