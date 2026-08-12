package com.se.frms.analytics.repository;

import com.se.frms.analytics.entity.FraudAnalytics;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudAnalyticsRepository extends JpaRepository<FraudAnalytics, UUID> {
}
