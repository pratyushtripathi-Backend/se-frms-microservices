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
        log.debug(
                "Publishing fraud event transactionId={}, decision={}, riskScore={}",
                event.transactionId(),
                event.fraudDecision(),
                event.totalRiskScore()
        );
        try {
            kafkaTemplate.send(fraudEventsTopic, event.transactionId().toString(), event)
                    // send() only queues the event; Kafka confirms (or fails) it later.
                    // Without this callback a failed send was silently lost - the
                    // dashboard alert, analytics and audit for it would never appear.
                    .whenComplete((result, failure) -> {
                        if (failure != null) {
                            log.error(
                                    "Fraud event publish FAILED transactionId={}, decision={}, topic={}, reason={}",
                                    event.transactionId(),
                                    event.fraudDecision(),
                                    fraudEventsTopic,
                                    failure.getMessage(),
                                    failure
                            );
                        } else if (log.isDebugEnabled()) {
                            log.debug(
                                    "Fraud event published transactionId={}, partition={}, offset={}",
                                    event.transactionId(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset()
                            );
                        }
                    });
            log.debug(
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
