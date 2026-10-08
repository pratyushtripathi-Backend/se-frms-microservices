package com.se.frms.scoring.config;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Thread pool for every @Async method in this service.
 *
 * Before: no executor was configured, so Spring Boot's default was used - 8 threads
 * with an unbounded queue. Under concurrent transactions only 8 ran at a time and
 * the rest waited in the queue, which is why concurrent requests took far longer
 * than sequential ones.
 * The bean is named "taskExecutor" so @Async always picks it, even when a
 * TaskScheduler bean also exists (scheduled cache refreshes).
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor(
            @Value("${frms.async.core-size:16}") int coreSize,
            @Value("${frms.async.max-size:32}") int maxSize,
            @Value("${frms.async.queue-capacity:1000}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Threads above core size are only started once the queue is full, so the
        // core size is the number of tasks that normally run at the same time.
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("frms-async-");
        // Never drop work: if pool and queue are ever full, the caller runs the task.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // Carry the correlation id (MDC) into async logs.
        executor.setTaskDecorator(copyMdc());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    private static TaskDecorator copyMdc() {
        return task -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (context == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(context);
                }
                try {
                    task.run();
                } finally {
                    if (previous == null) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(previous);
                    }
                }
            };
        };
    }
}
