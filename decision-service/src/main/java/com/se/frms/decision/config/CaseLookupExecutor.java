package com.se.frms.decision.config;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runs the case-list enrichment (one scoring-service + one transaction-service
 * lookup per row) for all rows of a page at the same time instead of one row after
 * another. Same calls, same fallbacks, same result order - only faster: a 20-row page
 * took 40 sequential HTTP calls before.
 *
 * The correlation id (MDC "requestId") is copied to the worker threads, so the
 * lookup requests carry the same X-Request-Id header and logs as before. An
 * exception thrown for a row is re-thrown unchanged to the caller (not wrapped),
 * exactly as with the previous sequential loop.
 */
@Component
public class CaseLookupExecutor {

    private final ExecutorService executor;

    public CaseLookupExecutor(@Value("${frms.cases.lookup-threads:20}") int threads) {
        AtomicInteger counter = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(Math.max(threads, 1), runnable -> {
            Thread thread = new Thread(runnable, "case-lookup-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Applies mapper to every item in parallel and returns the results in the same order. */
    public <T, R> List<R> mapInOrder(List<T> items, Function<T, R> mapper) {
        if (items.size() <= 1) {
            return items.stream().map(mapper).toList();
        }
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        List<CompletableFuture<R>> futures = new ArrayList<>(items.size());
        for (T item : items) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                if (mdc != null) {
                    MDC.setContextMap(mdc);
                }
                try {
                    return mapper.apply(item);
                } finally {
                    MDC.clear();
                }
            }, executor));
        }
        List<R> results = new ArrayList<>(items.size());
        for (CompletableFuture<R> future : futures) {
            try {
                results.add(future.join());
            } catch (CompletionException ex) {
                if (ex.getCause() instanceof RuntimeException cause) {
                    throw cause;
                }
                if (ex.getCause() instanceof Error error) {
                    throw error;
                }
                throw ex;
            }
        }
        return results;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
