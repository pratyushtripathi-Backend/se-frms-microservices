package com.se.frms.notification.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Makes sure the fraud-events topic has enough partitions for consumers to work
 * in parallel. The broker default (num.partitions=1) allowed only ONE consumer
 * thread per service, so notification-service handled every fraud event strictly
 * one after another.
 *
 * Spring's KafkaAdmin applies this on startup: it creates the topic if it does
 * not exist, and if it exists with fewer partitions it adds partitions (it never
 * removes any). Events are keyed by transactionId, so all events of one
 * transaction still go to the same partition and stay in order.
 *
 * Declared in both fraud-engine-service (producer) and notification-service
 * (consumer) so whichever starts first applies it; the second is a no-op.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic fraudEventsTopic(
            @Value("${frms.kafka.topic.fraud-events}") String topic,
            @Value("${frms.kafka.topic.fraud-events.partitions:6}") int partitions
    ) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
    }
}
