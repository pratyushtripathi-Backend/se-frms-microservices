package com.se.frms.transaction.config;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Runs the fraud-engine calls of consumed batches in parallel.
 *
 * Each evaluation mostly WAITS (HTTP call to fraud-engine, then one database update),
 * so the number of threads - not CPU - limited how many ran at the same time (128).
 *
 * Default: the fixed pool of frms.evaluation.threads threads, as before.
 * Optional (frms.evaluation.virtual-threads=true, Java 21+ only): each evaluation runs
 * on its own virtual thread, so concurrency is no longer capped by a thread count - how
 * many run at once is then set by frms.evaluation.max-in-flight (TransactionIngestConsumer)
 * and the HTTP connection pool. Only worth it if fraud-engine can serve more parallel
 * calls; on Java 17 the pool is used regardless.
 */
@Configuration
@Slf4j
public class EvaluationExecutorConfig {

    // Stopped before TransactionResultWriter, so results of evaluations finishing during
    // shutdown are still written by it.
    @Bean(name = "evaluationExecutor")
    @DependsOn("transactionResultWriter")
    public TaskExecutor evaluationExecutor(
            @Value("${frms.evaluation.threads:128}") int threads,
            @Value("${frms.evaluation.max-in-flight:128}") int maxInFlight,
            @Value("${frms.evaluation.virtual-threads:false}") boolean virtualThreads
    ) {
        if (virtualThreads) {
            ExecutorService virtual = newVirtualThreadPerTaskExecutor();
            if (virtual != null) {
                log.info("Fraud evaluations run on virtual threads (max in flight {})", maxInFlight);
                return new VirtualThreadTaskExecutor(virtual, copyMdc());
            }
            log.info("Virtual threads need Java 21+, using a pool of {} threads (Java {})",
                    threads, Runtime.version().feature());
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        // Large enough for everything TransactionIngestConsumer lets in flight, so the
        // consumer thread only runs an evaluation itself as a last resort.
        executor.setQueueCapacity(Math.max(threads * 2, maxInFlight));
        executor.setThreadNamePrefix("frms-eval-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(copyMdc());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /** Executors.newVirtualThreadPerTaskExecutor() on Java 21+, null on older Java (compiled for 17). */
    private static ExecutorService newVirtualThreadPerTaskExecutor() {
        if (Runtime.version().feature() < 21) {
            return null;
        }
        try {
            return (ExecutorService) java.util.concurrent.Executors.class
                    .getMethod("newVirtualThreadPerTaskExecutor")
                    .invoke(null);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            log.warn("Virtual threads not available, reason={}", ex.toString());
            return null;
        }
    }

    private static TaskDecorator copyMdc() {
        return task -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                try {
                    task.run();
                } finally {
                    MDC.clear();
                }
            };
        };
    }

    /** Virtual-thread executor; on shutdown waits up to 30 s for running evaluations (as the pool did). */
    static final class VirtualThreadTaskExecutor implements TaskExecutor, DisposableBean {

        private final ExecutorService delegate;
        private final TaskDecorator decorator;

        VirtualThreadTaskExecutor(ExecutorService delegate, TaskDecorator decorator) {
            this.delegate = delegate;
            this.decorator = decorator;
        }

        @Override
        public void execute(Runnable task) {
            delegate.execute(decorator.decorate(task));
        }

        @Override
        public void destroy() throws InterruptedException {
            delegate.shutdown();
            if (!delegate.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Fraud evaluations still running after 30 s at shutdown");
            }
        }
    }
}
