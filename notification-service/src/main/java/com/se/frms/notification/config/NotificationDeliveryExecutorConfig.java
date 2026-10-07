package com.se.frms.notification.config;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Runs the slow email / SMS provider calls (and their retries and SMS delivery-status
 * checks) off the Kafka consumer thread, so the consumer only saves + pushes the
 * dashboard alert and immediately moves on to the next fraud event. Dashboard alerts
 * therefore stay real time even in a burst, while email / SMS go out in parallel at
 * the providers' own speed.
 *
 * Email and SMS have separate pools, so a slow or unreachable SMS provider never
 * delays emails (and the other way round).
 */
@Configuration
public class NotificationDeliveryExecutorConfig {

    @Bean
    public ThreadPoolTaskExecutor emailDeliveryExecutor() {
        return deliveryExecutor("email-delivery-");
    }

    @Bean
    public ThreadPoolTaskExecutor smsDeliveryExecutor() {
        return deliveryExecutor("sms-delivery-");
    }

    /** One bean holding both pools, so services inject it by type (no name matching). */
    @Bean
    public NotificationDeliveryExecutors notificationDeliveryExecutors(
            @Qualifier("emailDeliveryExecutor") ThreadPoolTaskExecutor emailDeliveryExecutor,
            @Qualifier("smsDeliveryExecutor") ThreadPoolTaskExecutor smsDeliveryExecutor
    ) {
        return new NotificationDeliveryExecutors(emailDeliveryExecutor, smsDeliveryExecutor);
    }

    public record NotificationDeliveryExecutors(
            ThreadPoolTaskExecutor email,
            ThreadPoolTaskExecutor sms
    ) {
    }

    private static ThreadPoolTaskExecutor deliveryExecutor(String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Extra threads above core are only started once the queue is full, so the
        // core size is the normal number of sends running at the same time.
        executor.setCorePoolSize(6);
        executor.setMaxPoolSize(12);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix(threadNamePrefix);
        // Never drop a send: if the pool and queue are ever full, the submitting
        // thread sends it itself (the old behaviour) instead of losing it.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // Keeps the transaction's correlation id (MDC "requestId") in delivery logs.
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
