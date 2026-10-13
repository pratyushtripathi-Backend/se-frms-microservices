package com.se.frms.transaction.config;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Runs the fraud-engine calls of one consumed batch in parallel.
 *
 * Unlike the old @Async pool, nothing waits here for long: TransactionIngestConsumer
 * submits a batch and waits for it to finish before taking the next one from Kafka, so
 * the backlog stays in Kafka (on disk) and never in memory. If this pool is momentarily
 * full, the CONSUMER thread runs the call itself (CallerRunsPolicy) - which only slows
 * down reading from Kafka; the HTTP request threads are never involved any more.
 */
@Configuration
public class EvaluationExecutorConfig {

    @Bean(name = "evaluationExecutor")
    public ThreadPoolTaskExecutor evaluationExecutor(
            @Value("${frms.evaluation.threads:128}") int threads
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(threads * 2);
        executor.setThreadNamePrefix("frms-eval-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(task -> {
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
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
