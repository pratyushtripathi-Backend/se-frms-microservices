package com.se.frms.audit.consumer;
import com.se.frms.audit.dto.FraudEvent;
import com.se.frms.audit.service.AuditService;
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

    private final AuditService auditService;

    @KafkaListener(topics = "${frms.kafka.topic.fraud-events}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(FraudEvent event) {
        // Kafka message travels across processes, so it carries the
        // correlation ID as a payload field rather than an MDC value
        // (MDC is thread-local and can't cross the network). Re-establish
        // it here so this consumer's own logs trace back to the same
        // transaction that started in transaction-service.
        if (event.correlationId() != null) {
            MDC.put(MDC_KEY, event.correlationId());
        }
        try {
            long receivedAt = System.currentTimeMillis();
            log.info(
                    "Fraud event received for audit transactionId={}, decision={}, riskScore={}, eventOccurredAt={}",
                    event.transactionId(),
                    event.fraudDecision(),
                    event.totalRiskScore(),
                    event.occurredAt()
            );
            auditService.handleFraudEvent(event);
            log.info(
                    "Audit log persisted transactionId={}, elapsedSinceConsumeMs={}",
                    event.transactionId(),
                    System.currentTimeMillis() - receivedAt
            );
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
