package com.se.frms.notification.consumer;
import com.se.frms.notification.dto.FraudEvent;
import com.se.frms.notification.service.NotificationService;
import java.util.List;
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

    private final NotificationService notificationService;

    /**
     * Receives all fraud events of one poll at once (batch listener). Every alert of
     * the batch is pushed to the dashboard first and all rows are then inserted in one
     * transaction - see NotificationService.handleFraudEvents(). A failing event is
     * reported with its position, so Kafka retries from that event only (same as the
     * old one-event-at-a-time listener).
     */
    @KafkaListener(topics = "${frms.kafka.topic.fraud-events}", groupId = "${spring.kafka.consumer.group-id}",
            batch = "true")
    public void consume(List<FraudEvent> events) {
        long receivedAt = System.currentTimeMillis();
        for (FraudEvent event : events) {
            // MDC is thread-local and can't cross the network, so the
            // correlation ID travels as a field on the event itself and is
            // re-applied here for this consumer's own logs.
            withCorrelationId(event, () -> log.info(
                    "Fraud event received for notification transactionId={}, decision={}, riskScore={}, eventOccurredAt={}",
                    event == null ? null : event.transactionId(),
                    event == null ? null : event.fraudDecision(),
                    event == null ? null : event.totalRiskScore(),
                    event == null ? null : event.occurredAt()
            ));
        }
        notificationService.handleFraudEvents(events);
        long elapsed = System.currentTimeMillis() - receivedAt;
        for (FraudEvent event : events) {
            withCorrelationId(event, () -> log.info(
                    "Notification processing finished transactionId={}, elapsedSinceConsumeMs={}, batchSize={}",
                    event == null ? null : event.transactionId(),
                    elapsed,
                    events.size()
            ));
        }
    }

    private void withCorrelationId(FraudEvent event, Runnable work) {
        if (event != null && event.correlationId() != null) {
            MDC.put(MDC_KEY, event.correlationId());
        }
        try {
            work.run();
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
