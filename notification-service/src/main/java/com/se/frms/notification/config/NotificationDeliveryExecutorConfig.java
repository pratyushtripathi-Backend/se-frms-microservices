package com.se.frms.notification.config;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
 *
 * The Kafka consumer must NEVER run a provider call itself. Previously, once the
 * 500-slot queue was full (e.g. 250 BLOCK/REVIEW alerts at once x several admins),
 * CallerRunsPolicy made the consumer thread send the email / SMS (1-2 s each), so
 * every dashboard alert behind it waited - alerts arrived in waves, up to 25 s late.
 * Now the queue is large, and if it is ever full the send is NOT run on the caller:
 * execute() throws TaskRejectedException, NotificationServiceImpl leaves the
 * recipient PENDING in the database, and the existing recovery job
 * (recoverFailedDeliveries) sends it later. Nothing is dropped.
 */
@Configuration
public class NotificationDeliveryExecutorConfig {

    // Sends running at the same time per channel (same as the old maximum of 12).
    @Value("${notification.delivery.threads:12}")
    private int deliveryThreads;

    // Sends waiting per channel. ~2 KB each, so 10,000 is ~20 MB at most.
    @Value("${notification.delivery.queue-capacity:10000}")
    private int deliveryQueueCapacity;

    @Bean
    public ThreadPoolTaskExecutor emailDeliveryExecutor() {
        return deliveryExecutor("email-delivery-", deliveryThreads, deliveryQueueCapacity);
    }

    @Bean
    public ThreadPoolTaskExecutor smsDeliveryExecutor() {
        return deliveryExecutor("sms-delivery-", deliveryThreads, deliveryQueueCapacity);
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

    private static ThreadPoolTaskExecutor deliveryExecutor(String threadNamePrefix, int threads, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // core = max: a ThreadPoolExecutor only adds threads above core once the queue
        // is full, which with a large queue would never happen. Idle threads still
        // stop after 60 s (allowCoreThreadTimeOut), so a quiet service holds none.
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(60);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        // Full queue: reject (TaskRejectedException) instead of running the send on the
        // caller (the Kafka consumer). The caller leaves the recipient PENDING and the
        // recovery job sends it later - see NotificationServiceImpl.submitDelivery().
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
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
