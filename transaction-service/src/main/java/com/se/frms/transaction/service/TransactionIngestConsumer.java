package com.se.frms.transaction.service;

import com.se.frms.transaction.constant.TransactionStatus;
import com.se.frms.transaction.dto.FraudEvaluationRequest;
import com.se.frms.transaction.dto.TransactionReceivedEvent;
import com.se.frms.transaction.entity.TransactionMaster;
import com.se.frms.transaction.exception.FraudEngineBatchNotSupportedException;
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
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Consumes frms.transactions in batches:
 *  1. saves the batch's transactions with one batch INSERT (saveAll);
 *  2. then starts their fraud evaluations (same fraud-engine HTTP call as before) in
 *     parallel on evaluationExecutor and takes the next batch straight away.
 *
 * Saving BEFORE evaluating keeps today's behaviour: the transaction row always exists
 * when fraud-engine / decision-service look it up.
 *
 * The consumer no longer waits for the whole batch's evaluations: before, the next
 * batch of a partition waited for the SLOWEST evaluation of the previous one. At most
 * frms.evaluation.max-in-flight evaluations run at once; when that many are running,
 * the consumer waits for a free slot, so the backlog still stays in Kafka, not in memory.
 * Every transaction is saved (FRAUD_EVALUATION_IN_PROGRESS) before its offset is
 * committed, so if the service stops while evaluations are running, nothing is lost:
 * StuckTransactionRecoveryJob re-evaluates such rows.
 */
@Component
@Slf4j
public class TransactionIngestConsumer {

    private final TransactionRepository transactionRepository;
    private final TransactionEvaluationService transactionEvaluationService;
    private final DuplicateFraudNotificationService duplicateFraudNotificationService;
    private final Executor evaluationExecutor;
    // Limits evaluations running at the same time (frms.evaluation.max-in-flight).
    private final Semaphore inFlight;
    // true only for the 4-argument constructor (unit tests): wait for the batch's
    // evaluations before returning, as before.
    private final boolean awaitEvaluations;
    private final int maxInFlight;

    // Batch calls to fraud-engine (POST /api/v1/fraud-evaluations/batch). false outside
    // Spring (unit tests: one call per transaction, as before).
    @Value("${frms.fraud-engine.batch.enabled:true}")
    private boolean batchCallsEnabled = false;

    // Transactions per batch call (also capped by max-in-flight).
    @Value("${frms.fraud-engine.batch.size:20}")
    private int batchCallSize = 20;

    // Until when fraud-engine is known to have no batch endpoint (older version).
    private volatile long batchUnsupportedUntil;
    // Null only in unit tests built with the 4-argument constructor: then a row the
    // database refuses fails the batch exactly as before.
    private final TransactionDeadLetterPublisher deadLetterPublisher;

    public TransactionIngestConsumer(
            TransactionRepository transactionRepository,
            TransactionEvaluationService transactionEvaluationService,
            DuplicateFraudNotificationService duplicateFraudNotificationService,
            Executor evaluationExecutor
    ) {
        this(transactionRepository, transactionEvaluationService, duplicateFraudNotificationService,
                evaluationExecutor, null, Integer.MAX_VALUE, true);
    }

    @Autowired
    public TransactionIngestConsumer(
            TransactionRepository transactionRepository,
            TransactionEvaluationService transactionEvaluationService,
            DuplicateFraudNotificationService duplicateFraudNotificationService,
            @Qualifier("evaluationExecutor") Executor evaluationExecutor,
            TransactionDeadLetterPublisher deadLetterPublisher,
            @Value("${frms.evaluation.max-in-flight:128}") int maxInFlight
    ) {
        this(transactionRepository, transactionEvaluationService, duplicateFraudNotificationService,
                evaluationExecutor, deadLetterPublisher, maxInFlight, false);
    }

    private TransactionIngestConsumer(
            TransactionRepository transactionRepository,
            TransactionEvaluationService transactionEvaluationService,
            DuplicateFraudNotificationService duplicateFraudNotificationService,
            Executor evaluationExecutor,
            TransactionDeadLetterPublisher deadLetterPublisher,
            int maxInFlight,
            boolean awaitEvaluations
    ) {
        this.inFlight = new Semaphore(Math.max(1, maxInFlight));
        this.maxInFlight = Math.max(1, maxInFlight);
        this.awaitEvaluations = awaitEvaluations;
        this.transactionRepository = transactionRepository;
        this.transactionEvaluationService = transactionEvaluationService;
        this.duplicateFraudNotificationService = duplicateFraudNotificationService;
        this.evaluationExecutor = evaluationExecutor;
        this.deadLetterPublisher = deadLetterPublisher;
    }

    @KafkaListener(
            topics = "${frms.kafka.topic.transactions}",
            containerFactory = "transactionListenerFactory"
    )
    public void consume(List<ConsumerRecord<String, TransactionReceivedEvent>> records) {
        List<TransactionReceivedEvent> batch = records.stream().map(ConsumerRecord::value).filter(Objects::nonNull).toList();
        if (batch.isEmpty()) {
            return;
        }
        long startedAt = System.nanoTime();
        Map<UUID, Long> ingressTimes = new HashMap<>();
        for (ConsumerRecord<String, TransactionReceivedEvent> record : records) {
            if (record.value() == null) continue;
            var header = record.headers().lastHeader("frms-ingress-epoch-ms");
            if (header != null && header.value() != null) {
                try {
                    ingressTimes.put(record.value().id(), Long.parseLong(new String(header.value(), StandardCharsets.UTF_8)));
                } catch (NumberFormatException ignored) {
                    // Telemetry must never prevent processing old or malformed timing headers.
                }
            }
        }

        // Redelivered after a restart: skip rows that are already saved.
        Set<UUID> alreadySaved = new HashSet<>(transactionRepository.findExistingIds(
                batch.stream().map(TransactionReceivedEvent::id).toList()));

        List<TransactionMaster> toSave = new ArrayList<>();
        Map<UUID, TransactionReceivedEvent> eventById = new HashMap<>();
        Map<UUID, ConsumerRecord<String, TransactionReceivedEvent>> recordById = new HashMap<>();
        for (ConsumerRecord<String, TransactionReceivedEvent> record : records) {
            if (record.value() != null) {
                recordById.put(record.value().id(), record);
            }
        }
        LocalDateTime savedAt = LocalDateTime.now();
        for (TransactionReceivedEvent event : batch) {
            eventById.put(event.id(), event);
            if (!alreadySaved.contains(event.id())) {
                TransactionMaster entity = event.toEntity();
                if (TransactionStatus.FRAUD_EVALUATION_IN_PROGRESS.name().equals(entity.getStatus())) {
                    // Evaluation starts now; StuckTransactionRecoveryJob measures from here,
                    // so a transaction that waited in a Kafka backlog is never mistaken
                    // for a stuck one while it is being evaluated.
                    entity.setUpdatedAt(savedAt);
                }
                toSave.add(entity);
            }
        }
        markOldDuplicates(toSave);
        if (!toSave.isEmpty()) {
            toSave = save(toSave, recordById);
        }
        long saveMs = (System.nanoTime() - startedAt) / 1_000_000;

        List<CompletableFuture<Void>> evaluations = new ArrayList<>(batch.size());
        boolean useBatchCalls = batchCallsEnabled && System.currentTimeMillis() >= batchUnsupportedUntil;
        List<TransactionMaster> forBatchCalls = new ArrayList<>();
        for (TransactionMaster saved : toSave) {
            TransactionReceivedEvent event = eventById.get(saved.getId());
            if (useBatchCalls && !Boolean.TRUE.equals(saved.getDuplicateTransaction())) {
                forBatchCalls.add(saved);
                continue;
            }
            long queuedAt = System.nanoTime();
            evaluations.add(dispatch(() -> {
                log.debug("evaluation_queue transactionId={} executorWaitMs={}", event.id(), (System.nanoTime() - queuedAt) / 1_000_000.0);
                evaluate(saved, event, ingressTimes.get(event.id()));
            }, 1));
        }
        // Up to batchCallSize transactions per call to fraud-engine instead of one call each.
        int chunkSize = Math.max(1, Math.min(batchCallSize, maxInFlight));
        for (int from = 0; from < forBatchCalls.size(); from += chunkSize) {
            List<TransactionMaster> chunk = forBatchCalls.subList(from, Math.min(from + chunkSize, forBatchCalls.size()));
            List<TransactionEvaluationService.BatchEntry> entries = chunk.stream()
                    .map(saved -> {
                        TransactionReceivedEvent event = eventById.get(saved.getId());
                        return new TransactionEvaluationService.BatchEntry(saved.getId(), saved.getTransactionData(),
                                ingressTimes.get(saved.getId()), event.correlationId());
                    })
                    .toList();
            long queuedAt = System.nanoTime();
            evaluations.add(dispatch(() -> {
                log.debug("evaluation_queue batch size={} executorWaitMs={}", entries.size(), (System.nanoTime() - queuedAt) / 1_000_000.0);
                evaluateChunk(chunk, entries, eventById, ingressTimes);
            }, chunk.size()));
        }
        // Redelivered rows that were saved but not evaluated yet (service stopped mid-batch).
        for (UUID id : alreadySaved) {
            TransactionReceivedEvent event = eventById.get(id);
            if (!Boolean.TRUE.equals(event.duplicateTransaction())) {
                evaluations.add(dispatch(() -> evaluateIfStillPending(event, ingressTimes.get(event.id())), 1));
            }
        }
        if (awaitEvaluations) {
            CompletableFuture.allOf(evaluations.toArray(new CompletableFuture[0])).join();
        }

        log.info("Transaction batch processed size={}, saved={}, saveMs={}, elapsedMs={}",
                batch.size(), toSave.size(), saveMs, (System.nanoTime() - startedAt) / 1_000_000);
    }

    /**
     * Starts one evaluation, waiting first if max-in-flight evaluations are already
     * running. The permit is given back when the evaluation ends, whatever happens.
     */
    private CompletableFuture<Void> dispatch(Runnable evaluation, int permits) {
        try {
            inFlight.acquire(permits);
        } catch (InterruptedException ex) {
            // Service stopping: the row is saved FRAUD_EVALUATION_IN_PROGRESS and
            // StuckTransactionRecoveryJob evaluates it later.
            Thread.currentThread().interrupt();
            return CompletableFuture.completedFuture(null);
        }
        try {
            return CompletableFuture.runAsync(evaluation, evaluationExecutor)
                    .whenComplete((ignored, error) -> inFlight.release(permits));
        } catch (RuntimeException rejected) {
            // Executor shutting down: same as above, the recovery job evaluates it later.
            inFlight.release(permits);
            log.warn("Evaluation not started (executor stopping), reason={}", rejected.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Evaluates a chunk of saved transactions with one call to fraud-engine. If
     * fraud-engine has no batch endpoint (older version), they are evaluated one by one,
     * as before, and single calls are used for the next minute.
     */
    private void evaluateChunk(List<TransactionMaster> chunk, List<TransactionEvaluationService.BatchEntry> entries,
                               Map<UUID, TransactionReceivedEvent> eventById, Map<UUID, Long> ingressTimes) {
        try {
            transactionEvaluationService.evaluateBatch(entries);
        } catch (FraudEngineBatchNotSupportedException ex) {
            batchUnsupportedUntil = System.currentTimeMillis() + 60_000;
            log.warn("fraud-engine has no batch endpoint, evaluating one by one for the next minute");
            // Same single calls as before, in parallel (this chunk holds their slots).
            List<CompletableFuture<Void>> singles = new ArrayList<>(chunk.size());
            for (TransactionMaster saved : chunk) {
                TransactionReceivedEvent event = eventById.get(saved.getId());
                singles.add(CompletableFuture.runAsync(
                        () -> evaluate(saved, event, ingressTimes.get(saved.getId())), evaluationExecutor));
            }
            CompletableFuture.allOf(singles.toArray(new CompletableFuture[0])).join();
        } catch (RuntimeException ex) {
            // Same as a single evaluation's unexpected error: logged; the rows stay
            // FRAUD_EVALUATION_IN_PROGRESS and StuckTransactionRecoveryJob re-evaluates them.
            log.error("Fraud evaluation step failed for batch size={}", chunk.size(), ex);
        }
    }

    private void evaluate(TransactionMaster saved, TransactionReceivedEvent event, Long ingressEpochMs) {
        withCorrelationId(event.correlationId(), () -> {
            if (Boolean.TRUE.equals(saved.getDuplicateTransaction())) {
                // Same as before: duplicate is already decided; fraud-engine is only notified.
                duplicateFraudNotificationService.notifyFraudEngine(
                        new FraudEvaluationRequest(saved.getId(), saved.getTransactionData()));
            } else {
                transactionEvaluationService.evaluateNow(saved.getId(), saved.getTransactionData(), ingressEpochMs);
            }
        });
    }

    private void evaluateIfStillPending(TransactionReceivedEvent event, Long ingressEpochMs) {
        withCorrelationId(event.correlationId(), () -> {
            String inProgress = TransactionStatus.FRAUD_EVALUATION_IN_PROGRESS.name();
            List<Object[]> rows = transactionRepository.findStatusAndUpdatedAt(event.id());
            if (rows.isEmpty() || !inProgress.equals(rows.get(0)[0])) {
                return;
            }
            // Claim it first, so the recovery job (or another instance) cannot evaluate
            // the same transaction at the same time.
            LocalDateTime seenUpdatedAt = (LocalDateTime) rows.get(0)[1];
            if (seenUpdatedAt == null
                    || transactionRepository.claimForEvaluation(event.id(), inProgress, seenUpdatedAt, LocalDateTime.now()) == 1) {
                transactionEvaluationService.evaluateNow(event.id(), event.transactionData(), ingressEpochMs);
            }
        });
    }

    /**
     * Saves the batch with one batch INSERT (as before). If the database refuses the
     * batch because of the DATA of a row (e.g. a value too long for its column), the rows
     * are saved one by one and only the refused rows are moved to the dead-letter topic
     * (frms.transactions.DLT) - before, one such row blocked its whole partition forever.
     * Any other error (database down, ...) is thrown, and the batch is retried every 2 s
     * until it works, exactly as before.
     */
    private List<TransactionMaster> save(List<TransactionMaster> toSave,
                                         Map<UUID, ConsumerRecord<String, TransactionReceivedEvent>> recordById) {
        try {
            transactionRepository.saveAll(toSave);
            return toSave;
        } catch (RuntimeException ex) {
            if (deadLetterPublisher == null || !TransactionDeadLetterPublisher.isBadData(ex)) {
                throw ex;
            }
            log.warn("Batch insert refused by the database, saving one by one size={}, reason={}",
                    toSave.size(), ex.getMessage());
        }
        List<TransactionMaster> saved = new ArrayList<>(toSave.size());
        for (TransactionMaster transaction : toSave) {
            try {
                transactionRepository.save(transaction);
                saved.add(transaction);
            } catch (RuntimeException ex) {
                if (!TransactionDeadLetterPublisher.isBadData(ex)) {
                    throw ex;
                }
                deadLetterPublisher.publish(recordById.get(transaction.getId()), ex);
            }
        }
        return saved;
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
