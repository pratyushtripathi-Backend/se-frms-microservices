package com.se.frms.audit.consumer;
import com.se.frms.audit.dto.FraudEvent;
import com.se.frms.audit.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
@Component
@RequiredArgsConstructor
public class FraudEventConsumer {
    private final AuditService auditService;
    @KafkaListener(topics = "${frms.kafka.topic.fraud-events}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(FraudEvent event) {
        auditService.handleFraudEvent(event);
    }
}
