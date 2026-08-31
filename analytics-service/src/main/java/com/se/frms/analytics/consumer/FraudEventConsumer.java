package com.se.frms.analytics.consumer;
import com.se.frms.analytics.dto.FraudEvent;
import com.se.frms.analytics.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class FraudEventConsumer {
    private final AnalyticsService analyticsService;

    @KafkaListener(topics = "${frms.kafka.topic.fraud-events}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(FraudEvent event) {
        log.info(
                "Fraud event received for analytics transactionId={}, decision={}, riskScore={}",
                event.transactionId(),
                event.fraudDecision(),
                event.totalRiskScore()
        );
        analyticsService.handleFraudEvent(event);
    }
}
