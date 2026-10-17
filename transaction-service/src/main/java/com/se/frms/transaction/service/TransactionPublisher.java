package com.se.frms.transaction.service;

import com.se.frms.transaction.dto.TransactionReceivedEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.MDC;
import com.se.frms.transaction.filter.CorrelationIdFilter;
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
 *
 * publishAsync() does the same without holding the HTTP thread while Kafka confirms
 * the write: the POST still answers only after Kafka has stored the transaction, but
 * the Tomcat thread is free in the meantime, and with linger.ms several POSTs share
 * one Kafka write.
 */
@Component
@RequiredArgsConstructor
public class TransactionPublisher {

    private final KafkaTemplate<String, TransactionReceivedEvent> transactionKafkaTemplate;

    @Value("${frms.kafka.topic.transactions}")
    private String topic;

    public void publish(TransactionReceivedEvent event) {
        try {
            transactionKafkaTemplate.send(toRecord(event)).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable(ex);
        } catch (Exception ex) {
            throw unavailable(ex);
        }
    }

    /**
     * Same as publish(), but returns at once. The future completes when Kafka has
     * stored the transaction, or fails with the same 503 ResponseStatusException after
     * at most 10 s.
     */
    public CompletableFuture<Void> publishAsync(TransactionReceivedEvent event) {
        CompletableFuture<?> sent;
        try {
            sent = transactionKafkaTemplate.send(toRecord(event));
        } catch (Exception ex) {
            return CompletableFuture.failedFuture(unavailable(ex));
        }
        return sent
                .orTimeout(10, TimeUnit.SECONDS)
                .handle((result, ex) -> {
                    if (ex != null) {
                        Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                        throw unavailable(cause instanceof Exception e ? e : new RuntimeException(cause));
                    }
                    return null;
                });
    }

    private ProducerRecord<String, TransactionReceivedEvent> toRecord(TransactionReceivedEvent event) {
        ProducerRecord<String, TransactionReceivedEvent> record =
                new ProducerRecord<>(topic, event.id().toString(), event);
        String ingress = MDC.get(CorrelationIdFilter.INGRESS_MDC_KEY);
        if (ingress != null) {
            record.headers().add("frms-ingress-epoch-ms", ingress.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    private ResponseStatusException unavailable(Exception ex) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Transaction could not be accepted right now, please retry", ex);
    }
}
