package com.se.frms.transaction.service;

import com.se.frms.transaction.repository.TransactionRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes fraud results (status, remarks, updated_at) to se_frms_transaction_master.
 *
 * Before: one UPDATE and one commit per transaction - 2,000 transactions at once meant
 * 2,000 separate database round trips. Now results are queued and a background writer
 * saves everything waiting (up to frms.evaluation.result-batch-size) as ONE batched
 * UPDATE in one commit. Nothing waits for a batch to fill: a quiet service writes each
 * result immediately, a busy one writes many together.
 *
 * Same columns, same values: updated_at is still the time the result was known (set by
 * the caller), not the time of the write. If a batch fails, its rows are written one by
 * one with the previous single UPDATE; a row that still fails stays
 * FRAUD_EVALUATION_IN_PROGRESS and StuckTransactionRecoveryJob re-evaluates it, as before.
 * If the queue is full or the writer is stopped, the result is written immediately.
 */
@Component
@Slf4j
public class TransactionResultWriter {

    private static final String UPDATE_SQL =
            "UPDATE se_frms_transaction_master SET status = ?, remarks = ?, updated_at = ? WHERE id = ?";

    private record Result(UUID id, String status, String remarks, LocalDateTime updatedAt) {
    }

    private final TransactionRepository transactionRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    @Value("${frms.evaluation.result-batch-size:200}")
    private int batchSize = 200;

    @Value("${frms.evaluation.result-queue-capacity:50000}")
    private int queueCapacity = 50000;

    private volatile BlockingQueue<Result> pending;
    private volatile boolean running;
    private Thread writer;

    public TransactionResultWriter(TransactionRepository transactionRepository,
                                   JdbcTemplate jdbcTemplate,
                                   PlatformTransactionManager transactionManager) {
        this.transactionRepository = transactionRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @PostConstruct
    void start() {
        pending = new LinkedBlockingQueue<>(queueCapacity);
        running = true;
        writer = new Thread(this::writeLoop, "frms-result-writer");
        writer.setDaemon(true);
        writer.start();
    }

    /** Queues one fraud result; written within milliseconds. */
    public void write(UUID id, String status, String remarks, LocalDateTime updatedAt) {
        BlockingQueue<Result> queue = pending;
        if (queue != null && running && queue.offer(new Result(id, status, remarks, updatedAt))) {
            return;
        }
        // Writer not running (shutdown, plain unit test) or queue full: write now, as before.
        transactionRepository.updateEvaluationResult(id, status, remarks, updatedAt);
    }

    private void writeLoop() {
        List<Result> batch = new ArrayList<>(batchSize);
        while (running || !pending.isEmpty()) {
            try {
                Result first = pending.poll(200, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                pending.drainTo(batch, batchSize - 1);
                writeBatch(batch);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ex) {
                log.error("Transaction result writer error", ex);
            } finally {
                batch.clear();
            }
        }
    }

    private void writeBatch(List<Result> batch) {
        try {
            transactionTemplate.executeWithoutResult(status -> jdbcTemplate.batchUpdate(UPDATE_SQL, batch, batch.size(),
                    (ps, r) -> {
                        ps.setString(1, r.status());
                        ps.setString(2, r.remarks());
                        ps.setTimestamp(3, r.updatedAt() != null ? Timestamp.valueOf(r.updatedAt()) : null);
                        ps.setObject(4, r.id());
                    }));
        } catch (RuntimeException ex) {
            log.warn("Batched result update failed, writing one by one size={}, reason={}", batch.size(), ex.getMessage());
            for (Result r : batch) {
                try {
                    transactionRepository.updateEvaluationResult(r.id(), r.status(), r.remarks(), r.updatedAt());
                } catch (RuntimeException single) {
                    // Row stays FRAUD_EVALUATION_IN_PROGRESS; StuckTransactionRecoveryJob re-evaluates it.
                    log.error("Fraud result could not be saved transactionId={}, status={}", r.id(), r.status(), single);
                }
            }
        }
    }

    @PreDestroy
    void stop() {
        // Write everything still queued before the service stops.
        running = false;
        if (writer != null) {
            try {
                writer.join(15_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
