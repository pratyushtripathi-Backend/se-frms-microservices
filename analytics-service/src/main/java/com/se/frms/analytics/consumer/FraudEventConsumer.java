package com.se.frms.analytics.consumer;
import com.se.frms.analytics.dto.FraudEvent;
import com.se.frms.analytics.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class FraudEventConsumer {
    private static final String MDC_KEY = "requestId";

    private final AnalyticsService analyticsService;

    @KafkaListener(topics = "${frms.kafka.topic.fraud-events}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(FraudEvent event) {
        // MDC is thread-local and can't cross the network, so the
        // correlation ID travels as a field on the event itself and is
        // re-applied here for this consumer's own logs.
        if (event.correlationId() != null) {
            MDC.put(MDC_KEY, event.correlationId());
        }
        try {
            log.info(
                    "Fraud event received for analytics transactionId={}, decision={}, riskScore={}",
                    event.transactionId(),
                    event.fraudDecision(),
                    event.totalRiskScore()
            );
            analyticsService.handleFraudEvent(event);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
