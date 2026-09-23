package com.se.frms.analytics.service;
import com.se.frms.analytics.dto.AnalyticsSummaryResponse;
import com.se.frms.analytics.dto.ChannelCountResponse;
import com.se.frms.analytics.dto.DailyTransactionVolumeResponse;
import com.se.frms.analytics.dto.DecisionCountResponse;
import com.se.frms.analytics.dto.FraudEvent;
import com.se.frms.analytics.dto.FraudAnalyticsResponse;
import com.se.frms.analytics.dto.FraudTrendResponse;
import com.se.frms.analytics.dto.RulePerformanceResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AnalyticsService {
    void handleFraudEvent(FraudEvent event);

    /**
     * Applies a manual Allow/Block review (made in Case Management, via
     * decision-service) to this service's own copy of the decision, so
     * "Active Case" / reviewCount stops counting cases that have already
     * been resolved. Only touches fraud_decision - amount, channel,
     * triggeredRules and transactionData are left as originally recorded
     * by handleFraudEvent, since this event carries none of that detail.
     * A no-op (with a warning logged) if no row exists yet for the
     * transaction - the original fraud event just hasn't been consumed
     * yet, and there's nothing to correct.
     */
    void applyDecisionReview(UUID transactionId, String finalDecision);

    Page<FraudAnalyticsResponse> getAll(Pageable pageable, String fraudDecision, LocalDate fromDate, LocalDate toDate);

    FraudAnalyticsResponse getById(UUID analyticsId);

    FraudAnalyticsResponse getByTransactionId(UUID transactionId);

    AnalyticsSummaryResponse getSummary(LocalDate fromDate, LocalDate toDate);

    List<DecisionCountResponse> getDecisionCounts(LocalDate fromDate, LocalDate toDate);

    List<RulePerformanceResponse> getRulePerformance(int limit);

    List<DailyTransactionVolumeResponse> getDailyTransactionVolume(LocalDate fromDate, LocalDate toDate);

    List<ChannelCountResponse> getTransactionsByChannel(LocalDate fromDate, LocalDate toDate);

    List<FraudTrendResponse> getFraudTrend(String groupBy, LocalDate fromDate, LocalDate toDate);
}
