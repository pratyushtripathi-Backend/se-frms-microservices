package com.se.frms.scoring.service;

import com.se.frms.scoring.dto.RuleEvaluationResult;
import com.se.frms.scoring.entity.MatchedRule;
import com.se.frms.scoring.entity.Scoring;
import com.se.frms.scoring.repository.MatchedRuleRepository;
import com.se.frms.scoring.repository.ScoringRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Saves the Scoring row and its MatchedRule row after the scoring response has
 * already been returned - in batches by background writers (see saveScoring).
 *
 * Retry: before, a failed save (e.g. no free DB connection during a burst) was only
 * logged and the rows were lost. Now a failed attempt is retried after 1 s, 2 s and
 * 5 s (4 attempts, ~8 s in total) with exactly the same data:
 *  - createdDate / updatedAt are fixed at the first attempt, so a row saved on a
 *    retry carries the original time;
 *  - a retry first checks whether the Scoring row already exists (an earlier attempt
 *    may have committed even though it reported an error). Scoring and MatchedRule
 *    are saved in one transaction, so if Scoring exists both are saved and the retry
 *    stops - never a second MatchedRule row (getByScoringId expects exactly one);
 *  - retries wait on their own small scheduler, never by sleeping on the @Async pool,
 *    so scoring requests are not slowed down.
 * If the service stops while a retry is waiting, that save is lost (as before).
 */
@Service
@Slf4j
public class ScoringPersistenceService {

    private static final String SYSTEM_USER = "SCORING_SERVICE";
    private static final String MDC_KEY = "requestId";
    // Delay before attempt 2, 3 and 4.
    private static final long[] RETRY_DELAYS_MS = {1_000L, 2_000L, 5_000L};

    private final ScoringRepository scoringRepository;
    private final MatchedRuleRepository matchedRuleRepository;
    private final TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${frms.scoring.persist.batch-size:50}")
    private int batchSize = 50;

    @Value("${frms.scoring.persist.writer-threads:2}")
    private int writerThreads = 2;

    @Value("${frms.scoring.persist.queue-capacity:20000}")
    private int queueCapacity = 20000;

    private volatile BlockingQueue<PendingScoring> pending;
    private volatile boolean running;
    private final List<Thread> writers = new ArrayList<>();
    private final ScheduledExecutorService retryScheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "scoring-persist-retry");
        thread.setDaemon(true);
        return thread;
    });

    public ScoringPersistenceService(
            ScoringRepository scoringRepository,
            MatchedRuleRepository matchedRuleRepository,
            PlatformTransactionManager transactionManager
    ) {
        this.scoringRepository = scoringRepository;
        this.matchedRuleRepository = matchedRuleRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Queues the Scoring + MatchedRule rows for saving and returns at once (the scoring
     * response does not wait for the database, as before with @Async).
     *
     * Background writers take everything waiting (up to batch-size) and save it in ONE
     * database transaction with batched INSERTs, instead of one transaction and three
     * statements (SELECT + 2 INSERTs) per scoring. Nothing waits for a batch to fill:
     * a quiet service saves each scoring immediately, a busy one saves many together.
     * Same rows, same values; createdDate is still the time the save starts.
     * If a batch fails, each of its scorings is saved on its own with the existing
     * retry (1 s, 2 s, 5 s). If the queue is full, or the writers are not running
     * (e.g. a plain unit test), the scoring is saved right away, as before.
     */
    public void saveScoring(
            UUID scoringId,
            UUID transactionId,
            Integer totalRiskScore,
            List<RuleEvaluationResult> matchedResults
    ) {
        BlockingQueue<PendingScoring> queue = pending;
        if (queue != null && running
                && queue.offer(new PendingScoring(scoringId, transactionId, totalRiskScore, matchedResults, MDC.get(MDC_KEY)))) {
            return;
        }
        LocalDateTime createdAt = LocalDateTime.now();
        attempt(1, scoringId, transactionId, totalRiskScore, matchedResults, createdAt, System.nanoTime());
    }

    private record PendingScoring(UUID scoringId, UUID transactionId, Integer totalRiskScore,
                                  List<RuleEvaluationResult> matchedResults, String requestId) {
    }

    @PostConstruct
    void startWriters() {
        pending = new LinkedBlockingQueue<>(queueCapacity);
        running = true;
        for (int i = 1; i <= writerThreads; i++) {
            Thread writer = new Thread(this::writeLoop, "scoring-persist-" + i);
            writer.setDaemon(true);
            writer.start();
            writers.add(writer);
        }
    }

    private void writeLoop() {
        List<PendingScoring> batch = new ArrayList<>(batchSize);
        while (running || !pending.isEmpty()) {
            try {
                PendingScoring first = pending.poll(200, TimeUnit.MILLISECONDS);
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
                log.error("Scoring batch writer error", ex);
            } finally {
                batch.clear();
            }
        }
    }

    private void writeBatch(List<PendingScoring> batch) {
        LocalDateTime createdAt = LocalDateTime.now();
        long startedAt = System.nanoTime();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                for (PendingScoring item : batch) {
                    Scoring scoring = buildScoring(item.scoringId(), item.transactionId(), item.totalRiskScore(), createdAt);
                    // persist (not save): the id is new, so no SELECT before the INSERT.
                    entityManager.persist(scoring);
                    entityManager.persist(buildMatchedRule(scoring, item.matchedResults(), createdAt));
                }
            });
            log.debug("Scoring batch persisted size={}, elapsedMs={}", batch.size(), elapsedMillis(startedAt));
        } catch (RuntimeException ex) {
            log.warn("Scoring batch save failed, saving one by one size={}, reason={}", batch.size(), ex.getMessage());
            for (PendingScoring item : batch) {
                if (item.requestId() != null) {
                    MDC.put(MDC_KEY, item.requestId());
                }
                try {
                    attempt(1, item.scoringId(), item.transactionId(), item.totalRiskScore(),
                            item.matchedResults(), createdAt, startedAt);
                } finally {
                    MDC.remove(MDC_KEY);
                }
            }
        }
    }

    private void attempt(int attemptNo, UUID scoringId, UUID transactionId, Integer totalRiskScore,
                         List<RuleEvaluationResult> matchedResults, LocalDateTime createdAt, long startedAt) {
        try {
            Boolean saved = transactionTemplate.execute(status -> {
                if (attemptNo > 1 && scoringRepository.existsById(scoringId)) {
                    return false; // an earlier attempt did commit - nothing to do
                }
                Scoring savedScoring = scoringRepository.save(buildScoring(scoringId, transactionId, totalRiskScore, createdAt));
                matchedRuleRepository.save(buildMatchedRule(savedScoring, matchedResults, createdAt));
                return true;
            });

            if (Boolean.TRUE.equals(saved)) {
                log.debug(
                        "Scoring persisted asynchronously transactionId={}, scoringId={}, matchedRuleCount={}, attempt={}, elapsedMs={}",
                        transactionId,
                        scoringId,
                        matchedResults.size(),
                        attemptNo,
                        elapsedMillis(startedAt)
                );
            } else {
                log.info("Scoring already persisted by an earlier attempt transactionId={}, scoringId={}",
                        transactionId, scoringId);
            }
        } catch (RuntimeException ex) {
            if (attemptNo > RETRY_DELAYS_MS.length) {
                log.error(
                        "Async scoring persistence failed after {} attempts transactionId={}, scoringId={}",
                        attemptNo,
                        transactionId,
                        scoringId,
                        ex
                );
                return;
            }
            long delayMs = RETRY_DELAYS_MS[attemptNo - 1];
            log.warn(
                    "Async scoring persistence failed transactionId={}, scoringId={}, attempt={} - retrying in {} ms: {}",
                    transactionId,
                    scoringId,
                    attemptNo,
                    delayMs,
                    ex.getMessage()
            );
            String requestId = MDC.get(MDC_KEY);
            retryScheduler.schedule(() -> {
                if (requestId != null) {
                    MDC.put(MDC_KEY, requestId);
                }
                try {
                    attempt(attemptNo + 1, scoringId, transactionId, totalRiskScore, matchedResults, createdAt, startedAt);
                } finally {
                    MDC.remove(MDC_KEY);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    @PreDestroy
    void shutdown() {
        // Save what is still queued before the service stops.
        running = false;
        for (Thread writer : writers) {
            try {
                writer.join(15_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        retryScheduler.shutdownNow();
    }

    private Scoring buildScoring(UUID scoringId, UUID transactionId, Integer totalRiskScore, LocalDateTime createdAt) {
        Scoring scoring = new Scoring();
        scoring.setId(scoringId);
        scoring.setTransactionId(transactionId);
        scoring.setTotalRiskScore(totalRiskScore);
        scoring.setStatus(true);
        scoring.setCreatedBy(SYSTEM_USER);
        scoring.setCreatedDate(createdAt);
        scoring.setUpdatedAt(createdAt);
        return scoring;
    }

    private MatchedRule buildMatchedRule(Scoring scoring, List<RuleEvaluationResult> results, LocalDateTime createdAt) {
        MatchedRule matchedRule = new MatchedRule();
        matchedRule.setScoring(scoring);
        matchedRule.setRuleCodes(results.stream().map(result -> result.rule().ruleCode()).toList());
        matchedRule.setRuleNames(results.stream().map(result -> result.rule().ruleName()).toList());
        matchedRule.setRuleExpressions(results.stream().map(result -> result.rule().ruleExpression()).toList());
        matchedRule.setRuleScores(results.stream().map(result -> result.rule().ruleScore()).toList());
        matchedRule.setCalculatedScores(results.stream().map(RuleEvaluationResult::calculatedScore).toList());
        matchedRule.setStatus(true);
        matchedRule.setCreatedBy(SYSTEM_USER);
        matchedRule.setCreatedDate(createdAt);
        matchedRule.setUpdatedAt(createdAt);
        return matchedRule;
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
