package com.se.frms.transaction.service;

import com.se.frms.transaction.constant.TransactionStatus;
import com.se.frms.transaction.dto.FraudEvaluationRequest;
import com.se.frms.transaction.dto.TransactionReceivedEvent;
import com.se.frms.transaction.entity.TransactionMaster;
import com.se.frms.transaction.filter.CorrelationIdFilter;
import com.se.frms.transaction.repository.TransactionRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Consumes frms.transactions in batches:
 *  1. saves the batch's transactions with one batch INSERT (saveAll);
 *  2. then runs their fraud evaluations (same fraud-engine HTTP call as before) in
 *     parallel on evaluationExecutor, and waits for them before taking the next batch.
 *
 * Saving BEFORE evaluating keeps today's behaviour: the transaction row always exists
 * when fraud-engine / decision-service look it up. Waiting for the batch keeps the
 * backlog in Kafka instead of in memory.
 */
@Component
@Slf4j
public class TransactionIngestConsumer {

    private final TransactionRepository transactionRepository;
    private final TransactionEvaluationService transactionEvaluationService;
    private final DuplicateFraudNotificationService duplicateFraudNotificationService;
    private final ThreadPoolTaskExecutor evaluationExecutor;

    public TransactionIngestConsumer(
            TransactionRepository transactionRepository,
            TransactionEvaluationService transactionEvaluationService,
            DuplicateFraudNotificationService duplicateFraudNotificationService,
            @Qualifier("evaluationExecutor") ThreadPoolTaskExecutor evaluationExecutor
    ) {
        this.transactionRepository = transactionRepository;
        this.transactionEvaluationService = transactionEvaluationService;
        this.duplicateFraudNotificationService = duplicateFraudNotificationService;
        this.evaluationExecutor = evaluationExecutor;
    }

    @KafkaListener(
            topics = "${frms.kafka.topic.transactions}",
            containerFactory = "transactionListenerFactory"
    )
    public void consume(List<TransactionReceivedEvent> events) {
        List<TransactionReceivedEvent> batch = events.stream().filter(Objects::nonNull).toList();
        if (batch.isEmpty()) {
            return;
        }
        long startedAt = System.nanoTime();

        // Redelivered after a restart: skip rows that are already saved.
        Set<UUID> alreadySaved = new HashSet<>(transactionRepository.findExistingIds(
                batch.stream().map(TransactionReceivedEvent::id).toList()));

        List<TransactionMaster> toSave = new ArrayList<>();
        Map<UUID, TransactionReceivedEvent> eventById = new HashMap<>();
        for (TransactionReceivedEvent event : batch) {
            eventById.put(event.id(), event);
            if (!alreadySaved.contains(event.id())) {
                toSave.add(event.toEntity());
            }
        }
        markOldDuplicates(toSave);
        if (!toSave.isEmpty()) {
            transactionRepository.saveAll(toSave);
        }
        long saveMs = (System.nanoTime() - startedAt) / 1_000_000;

        List<CompletableFuture<Void>> evaluations = new ArrayList<>(batch.size());
        for (TransactionMaster saved : toSave) {
            TransactionReceivedEvent event = eventById.get(saved.getId());
            evaluations.add(CompletableFuture.runAsync(() -> evaluate(saved, event), evaluationExecutor));
        }
        // Redelivered rows that were saved but not evaluated yet (service stopped mid-batch).
        for (UUID id : alreadySaved) {
            TransactionReceivedEvent event = eventById.get(id);
            if (!Boolean.TRUE.equals(event.duplicateTransaction())) {
                evaluations.add(CompletableFuture.runAsync(() -> evaluateIfStillPending(event), evaluationExecutor));
            }
        }
        CompletableFuture.allOf(evaluations.toArray(new CompletableFuture[0])).join();

        log.info("Transaction batch processed size={}, saved={}, saveMs={}, elapsedMs={}",
                batch.size(), toSave.size(), saveMs, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private void evaluate(TransactionMaster saved, TransactionReceivedEvent event) {
        withCorrelationId(event.correlationId(), () -> {
            if (Boolean.TRUE.equals(saved.getDuplicateTransaction())) {
                // Same as before: duplicate is already decided; fraud-engine is only notified.
                duplicateFraudNotificationService.notifyFraudEngine(
                        new FraudEvaluationRequest(saved.getId(), saved.getTransactionData()));
            } else {
                transactionEvaluationService.evaluateNow(saved.getId(), saved.getTransactionData());
            }
        });
    }

    private void evaluateIfStillPending(TransactionReceivedEvent event) {
        withCorrelationId(event.correlationId(), () -> {
            String status = transactionRepository.findStatusById(event.id()).orElse(null);
            if (TransactionStatus.FRAUD_EVALUATION_IN_PROGRESS.name().equals(status)) {
                transactionEvaluationService.evaluateNow(event.id(), event.transactionData());
            }
        });
    }

    /**
     * Safety net behind the Redis check: a transaction whose externalTransactionId
     * already exists in the database from an EARLIER request (e.g. the Redis key had
     * expired) is saved as a duplicate, exactly as the old database check did.
     */
    private void markOldDuplicates(List<TransactionMaster> toSave) {
        Set<UUID> batchIds = new HashSet<>();
        Map<String, TransactionMaster> newOnes = new HashMap<>();
        for (TransactionMaster t : toSave) {
            batchIds.add(t.getId());
            if (!Boolean.TRUE.equals(t.getDuplicateTransaction()) && StringUtils.hasText(t.getExternalTransactionId())) {
                TransactionMaster first = newOnes.putIfAbsent(t.getExternalTransactionId(), t);
                if (first != null) {
                    // Same externalTransactionId twice in one batch (only possible if Redis was down).
                    markAsDuplicate(t, first.getId());
                }
            }
        }
        if (newOnes.isEmpty()) {
            return;
        }
        Map<String, Object[]> earliest = new HashMap<>();
        for (Object[] row : transactionRepository.findIdsByExternalTransactionIds(newOnes.keySet())) {
            UUID id = (UUID) row[0];
            if (batchIds.contains(id)) {
                continue;
            }
            String externalId = (String) row[1];
            Object[] current = earliest.get(externalId);
            LocalDateTime created = (LocalDateTime) row[2];
            if (current == null || (created != null && created.isBefore((LocalDateTime) current[2]))) {
                earliest.put(externalId, row);
            }
        }
        earliest.forEach((externalId, row) -> {
            TransactionMaster t = newOnes.get(externalId);
            log.warn("Duplicate transaction detected at save externalTransactionId={}, originalTransactionId={}",
                    externalId, row[0]);
            markAsDuplicate(t, (UUID) row[0]);
        });
    }

    private void markAsDuplicate(TransactionMaster transaction, UUID originalId) {
        transaction.setStatus(TransactionStatus.DUPLICATE_FRAUD.name());
        transaction.setDuplicateTransaction(true);
        transaction.setOriginalTransactionId(originalId);
        transaction.setRemarks("Duplicate idempotent transaction detected for externalTransactionId "
                + transaction.getExternalTransactionId());
        Map<String, Object> data = new HashMap<>(transaction.getTransactionData());
        data.put("duplicateTransaction", true);
        data.put("duplicateReason", "IDEMPOTENT_TRANSACTION_RETRY");
        data.put("originalTransactionId", originalId.toString());
        data.put("fraudSignal", "DUPLICATE_EXTERNAL_TRANSACTION_ID");
        transaction.setTransactionData(data);
    }

    private void withCorrelationId(String correlationId, Runnable work) {
        if (correlationId != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        }
        try {
            work.run();
        } catch (RuntimeException ex) {
            log.error("Fraud evaluation step failed", ex);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
