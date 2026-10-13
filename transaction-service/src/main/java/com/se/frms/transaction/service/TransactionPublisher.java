package com.se.frms.transaction.service;

import com.se.frms.transaction.dto.TransactionReceivedEvent;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Publishes a received transaction to frms.transactions and waits until Kafka has
 * stored it, so the POST only answers "accepted" for a transaction that can no longer
 * be lost. If Kafka is unavailable the POST fails with 503 (the caller retries).
 */
@Component
@RequiredArgsConstructor
public class TransactionPublisher {

    private final KafkaTemplate<String, TransactionReceivedEvent> transactionKafkaTemplate;

    @Value("${frms.kafka.topic.transactions}")
    private String topic;

    public void publish(TransactionReceivedEvent event) {
        try {
            transactionKafkaTemplate.send(topic, event.id().toString(), event).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable(ex);
        } catch (Exception ex) {
            throw unavailable(ex);
        }
    }

    private ResponseStatusException unavailable(Exception ex) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Transaction could not be accepted right now, please retry", ex);
    }
}
