package com.se.frms.transaction.service;

import com.se.frms.transaction.constant.TransactionStatus;
import com.se.frms.transaction.entity.TransactionMaster;
import com.se.frms.transaction.repository.TransactionRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Safety net for transactions left in FRAUD_EVALUATION_IN_PROGRESS: e.g. the service
 * stopped during the fraud-engine call, or the call failed with an error that was only
 * logged. Before, such a transaction stayed "in progress" for ever.
 *
 * Every minute, transactions still in progress more than 5 minutes after their
 * evaluation started (and less than 24 hours old - older ones are left untouched) are
 * evaluated again with the normal fraud evaluation, at most 100 per run. Each one is
 * claimed in the database first, so it is never evaluated twice at the same time
 * (also with several instances). Normal evaluations finish in well under a minute, so
 * they are never picked up.
 */
@Component
@Slf4j
public class StuckTransactionRecoveryJob {

    private final TransactionRepository transactionRepository;
    private final TransactionEvaluationService transactionEvaluationService;
    private final Executor evaluationExecutor;

    @Value("${frms.recovery.stuck-after-minutes:5}")
    private long stuckAfterMinutes;

    @Value("${frms.recovery.max-age-hours:24}")
    private long maxAgeHours;

    @Value("${frms.recovery.batch-size:100}")
    private int batchSize;

    public StuckTransactionRecoveryJob(
            TransactionRepository transactionRepository,
            TransactionEvaluationService transactionEvaluationService,
            @Qualifier("evaluationExecutor") Executor evaluationExecutor
    ) {
        this.transactionRepository = transactionRepository;
        this.transactionEvaluationService = transactionEvaluationService;
        this.evaluationExecutor = evaluationExecutor;
    }

    @Scheduled(
            initialDelayString = "${frms.recovery.interval-ms:60000}",
            fixedDelayString = "${frms.recovery.interval-ms:60000}"
    )
    public void recoverStuckTransactions() {
        try {
            recover();
        } catch (RuntimeException ex) {
            // e.g. database down: try again next run.
            log.warn("Stuck transaction recovery skipped this run, reason={}", ex.getMessage());
        }
    }

    void recover() {
        String inProgress = TransactionStatus.FRAUD_EVALUATION_IN_PROGRESS.name();
        LocalDateTime now = LocalDateTime.now();
        List<Object[]> candidates = transactionRepository.findStuckTransactions(
                inProgress, now.minusMinutes(stuckAfterMinutes), now.minusHours(maxAgeHours),
                PageRequest.of(0, batchSize));
        if (candidates.isEmpty()) {
            return;
        }
        List<UUID> claimed = new ArrayList<>();
        for (Object[] row : candidates) {
            UUID id = (UUID) row[0];
            if (transactionRepository.claimForEvaluation(id, inProgress, (LocalDateTime) row[1], LocalDateTime.now()) == 1) {
                claimed.add(id);
            }
        }
        if (claimed.isEmpty()) {
            return;
        }
        log.warn("Re-evaluating transactions stuck in {} count={}, ids={}", inProgress, claimed.size(), claimed);
        List<CompletableFuture<Void>> evaluations = new ArrayList<>(claimed.size());
        for (TransactionMaster transaction : transactionRepository.findAllById(claimed)) {
            evaluations.add(CompletableFuture.runAsync(() -> {
                try {
                    transactionEvaluationService.evaluateNow(transaction.getId(), transaction.getTransactionData());
                } catch (RuntimeException ex) {
                    log.error("Re-evaluation failed transactionId={} (will be retried)", transaction.getId(), ex);
                }
            }, evaluationExecutor));
        }
        CompletableFuture.allOf(evaluations.toArray(new CompletableFuture[0])).join();
    }
}
