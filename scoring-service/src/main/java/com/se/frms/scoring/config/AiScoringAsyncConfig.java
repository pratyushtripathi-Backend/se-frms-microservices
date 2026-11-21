package com.se.frms.scoring.config;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Separate, bounded thread pool for the AI shadow-scoring call only - deliberately NOT the
 * shared "taskExecutor" bean (AsyncConfig), whose CallerRunsPolicy would make the HTTP request
 * thread itself run a Claude call (seconds) the moment that pool saturates, blowing the
 * sub-200ms scoring budget under the exact high-volume (1000+ transaction) conditions this
 * exists to survive.
 *
 * Here the opposite policy applies: if this small pool and its queue are ever full, the AI-score
 * attempt for that transaction is dropped (logged once, not thrown) rather than queued without
 * bound or run inline. The rule-based response has already been returned long before this
 * executor is touched - a dropped AI score only means that one transaction has no AI/combined
 * score to evaluate later, never a slower or failed response.
 */
@Configuration
@Slf4j
public class AiScoringAsyncConfig {

    @Bean(name = "aiScoringExecutor")
    public ThreadPoolTaskExecutor aiScoringExecutor(
            @Value("${frms.ai.async.core-size:5}") int coreSize,
            @Value("${frms.ai.async.max-size:10}") int maxSize,
            @Value("${frms.ai.async.queue-capacity:500}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("ai-scoring-");
        executor.setRejectedExecutionHandler(loggingDiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    private static RejectedExecutionHandler loggingDiscardPolicy() {
        return (task, pool) -> log.warn(
                "AI scoring task dropped - pool saturated (active={}, queueSize={}); "
                        + "that transaction will have no AI/combined score",
                pool.getActiveCount(),
                pool.getQueue().size()
        );
    }
}
