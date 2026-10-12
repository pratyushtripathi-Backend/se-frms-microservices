package com.se.frms.fraudengine.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Opens the Kafka producer connection and loads the fraud-events topic's partition
 * details once at startup, so the first fraud event after a (re)start does not pay
 * for it (that first send used to wait 100-270 ms).
 *
 * Speed only: nothing is published here. Best effort: it runs on its own thread
 * and, if Kafka is not reachable, only logs a warning - startup and the normal
 * publish path (FraudEventProducer) are unchanged.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaProducerWarmUp {

    private final KafkaTemplate<?, ?> kafkaTemplate;

    @Value("${frms.kafka.topic.fraud-events}")
    private String fraudEventsTopic;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        Thread thread = new Thread(this::loadTopicMetadata, "kafka-producer-warm-up");
        thread.setDaemon(true);
        thread.start();
    }

    private void loadTopicMetadata() {
        long startedAt = System.nanoTime();
        try {
            int partitions = kafkaTemplate.partitionsFor(fraudEventsTopic).size();
            log.info("Kafka producer warmed up topic={}, partitions={}, elapsedMs={}",
                    fraudEventsTopic, partitions, (System.nanoTime() - startedAt) / 1_000_000);
        } catch (RuntimeException ex) {
            log.warn("Kafka producer warm-up skipped topic={}, reason={} (events are still published normally)",
                    fraudEventsTopic, ex.getMessage());
        }
    }
}
