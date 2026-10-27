package com.se.frms.fraudengine.config;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Threads for POST /api/v1/fraud-evaluations/batch: prepares the transactions of a batch
 * in parallel (location lookup, blacklist matching), and runs the one-by-one fallback
 * calls if Scoring / Decision Service has no batch endpoint. Separate from the @Async
 * pool so a batch never waits behind fraud-event publishing.
 */
@Component
public class FraudBatchExecutor implements Executor, DisposableBean {

    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    public FraudBatchExecutor(@Value("${frms.fraud-evaluation.batch.threads:64}") int threads) {
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(10_000);
        executor.setThreadNamePrefix("fraud-batch-");
        // Last resort only: the request thread does the work itself.
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
    }

    @Override
    public void execute(Runnable task) {
        executor.execute(task);
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }
}
