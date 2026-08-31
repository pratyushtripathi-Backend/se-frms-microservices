package com.se.frms.audit.consumer;
import com.se.frms.audit.dto.FraudEvent;
import com.se.frms.audit.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class FraudEventConsumer {
    private final AuditService auditService;

    @KafkaListener(topics = "${frms.kafka.topic.fraud-events}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(FraudEvent event) {
        log.info(
                "Fraud event received for audit transactionId={}, decision={}, riskScore={}",
                event.transactionId(),
                event.fraudDecision(),
                event.totalRiskScore()
        );
        auditService.handleFraudEvent(event);
    }
}
