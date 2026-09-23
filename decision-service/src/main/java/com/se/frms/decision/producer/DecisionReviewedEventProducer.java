package com.se.frms.decision.producer;

import com.se.frms.decision.dto.DecisionReviewedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

// Mirrors fraud-engine-service's FraudEventProducer: fire-and-forget so a
// manual Allow/Block never fails or slows down on Kafka being unavailable.
// A skipped publish only means analytics-service's copy stays stale until
// the next successful one for that transaction - it never blocks or loses
// the actual review decision, which is always persisted in decision-service
// first regardless of whether this publish succeeds.
@Component
@RequiredArgsConstructor
@Slf4j
public class DecisionReviewedEventProducer {

    private final KafkaTemplate<String, DecisionReviewedEvent> kafkaTemplate;

    @Value("${frms.kafka.topic.decision-reviewed}")
    private String decisionReviewedTopic;

    @Async
    public void publish(DecisionReviewedEvent event) {
        long startedAt = System.nanoTime();
        log.info(
                "Publishing decision-reviewed event transactionId={}, previousDecision={}, finalDecision={}",
                event.transactionId(),
                event.previousDecision(),
                event.finalDecision()
        );
        try {
            kafkaTemplate.send(decisionReviewedTopic, event.transactionId().toString(), event);
            log.info(
                    "Decision-reviewed event publish triggered transactionId={}, elapsedMs={}",
                    event.transactionId(),
                    elapsedMillis(startedAt)
            );
        } catch (RuntimeException ex) {
            log.warn(
                    "Decision-reviewed event publish skipped transactionId={}, reason={}",
                    event.transactionId(),
                    ex.getMessage()
            );
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
