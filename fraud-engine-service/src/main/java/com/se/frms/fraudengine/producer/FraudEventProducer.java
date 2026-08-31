package com.se.frms.fraudengine.producer;

import com.se.frms.fraudengine.dto.FraudEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class FraudEventProducer {

    private final KafkaTemplate<String, FraudEvent> kafkaTemplate;

    @Value("${frms.kafka.topic.fraud-events}")
    private String fraudEventsTopic;

    @Async
    public void publish(FraudEvent event) {
        long startedAt = System.nanoTime();
        log.info(
                "Publishing fraud event transactionId={}, decision={}, riskScore={}",
                event.transactionId(),
                event.fraudDecision(),
                event.totalRiskScore()
        );
        try {
            kafkaTemplate.send(fraudEventsTopic, event.transactionId().toString(), event);
            log.info(
                    "Fraud event publish triggered transactionId={}, elapsedMs={}",
                    event.transactionId(),
                    elapsedMillis(startedAt)
            );
        } catch (RuntimeException ex) {
            log.warn(
                    "Fraud event publish skipped transactionId={}, reason={}",
                    event.transactionId(),
                    ex.getMessage()
            );
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
