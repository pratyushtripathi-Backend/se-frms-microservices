package com.se.frms.transaction.service;

import com.se.frms.transaction.dto.TransactionReceivedEvent;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Moves a transaction the database refuses to save (bad data, not an outage) to the
 * dead-letter topic frms.transactions.DLT, so it no longer blocks the rest of its
 * partition. The record is kept there unchanged (same key, same JSON, same headers)
 * plus headers saying where it came from and why, so it can be fixed and re-sent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TransactionDeadLetterPublisher {

    private final KafkaTemplate<String, TransactionReceivedEvent> transactionKafkaTemplate;

    @Value("${frms.kafka.topic.transactions.dlt:frms.transactions.DLT}")
    private String deadLetterTopic;

    public void publish(ConsumerRecord<String, TransactionReceivedEvent> record, Exception reason) {
        ProducerRecord<String, TransactionReceivedEvent> dead =
                new ProducerRecord<>(deadLetterTopic, null, record.key(), record.value(), record.headers());
        dead.headers().add("frms-dlt-original-topic", bytes(record.topic()));
        dead.headers().add("frms-dlt-original-partition", bytes(Integer.toString(record.partition())));
        dead.headers().add("frms-dlt-original-offset", bytes(Long.toString(record.offset())));
        dead.headers().add("frms-dlt-reason", bytes(rootMessage(reason)));
        try {
            // Wait for Kafka: the batch is only committed once the row is safely parked.
            transactionKafkaTemplate.send(dead).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Dead-letter publish interrupted", ex);
        } catch (Exception ex) {
            // Thrown on: the whole batch is retried, nothing is lost.
            throw new IllegalStateException("Dead-letter publish failed for transactionId="
                    + (record.value() != null ? record.value().id() : null), ex);
        }
        log.error("Transaction moved to dead-letter topic {} transactionId={}, externalTransactionId={}, reason={}",
                deadLetterTopic,
                record.value() != null ? record.value().id() : null,
                record.value() != null ? record.value().externalTransactionId() : null,
                rootMessage(reason));
    }

    /**
     * True when the database refused the row because of its data (constraint
     * violation, value too long / invalid - SQLState class 22 or 23). Such a row
     * would fail on every retry. Everything else (connection lost, timeout, ...)
     * returns false and is retried as before.
     */
    public static boolean isBadData(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof DataIntegrityViolationException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null
                    && (sql.getSQLState().startsWith("22") || sql.getSQLState().startsWith("23"))) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() != null ? root.getMessage() : root.getClass().getName();
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private static byte[] bytes(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }
}
