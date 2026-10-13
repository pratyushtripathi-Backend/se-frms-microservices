package com.se.frms.transaction.config;

import com.se.frms.transaction.dto.TransactionReceivedEvent;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka set-up for the transaction queue (topic frms.transactions), used only inside
 * transaction-service: the POST publishes, TransactionIngestConsumer consumes.
 *
 * Replaces the in-memory @Async thread pool (32-64 threads, queue 1000,
 * CallerRunsPolicy) that used to run every fraud evaluation: under a burst that pool
 * filled up and the HTTP request thread itself ran the whole evaluation, and a restart
 * lost everything still queued. Kafka keeps the queue on disk and spreads it over
 * consumer threads.
 */
@Configuration
public class TransactionKafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public NewTopic transactionsTopic(
            @Value("${frms.kafka.topic.transactions}") String topic,
            @Value("${frms.kafka.topic.transactions.partitions:12}") int partitions
    ) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
    }

    @Bean
    public ProducerFactory<String, TransactionReceivedEvent> transactionProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        // The POST only answers once Kafka has stored the transaction safely.
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        // Keep the connection and topic details between quiet periods.
        props.put(ProducerConfig.METADATA_MAX_IDLE_CONFIG, 86_400_000);
        props.put(ProducerConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG, 3_600_000);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, TransactionReceivedEvent> transactionKafkaTemplate(
            ProducerFactory<String, TransactionReceivedEvent> transactionProducerFactory
    ) {
        return new KafkaTemplate<>(transactionProducerFactory);
    }

    @Bean
    public ConsumerFactory<String, TransactionReceivedEvent> transactionConsumerFactory(
            @Value("${frms.kafka.transactions.max-poll-records:100}") int maxPollRecords
    ) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "transaction-service-ingest");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        // A batch includes its fraud evaluations; allow plenty of time before Kafka
        // considers the consumer stuck.
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 50);
        JsonDeserializer<TransactionReceivedEvent> valueDeserializer =
                new JsonDeserializer<>(TransactionReceivedEvent.class, false);
        valueDeserializer.addTrustedPackages("com.se.frms.transaction.dto");
        // A message that cannot be read is logged and skipped instead of blocking the partition.
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                new ErrorHandlingDeserializer<>(valueDeserializer));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionReceivedEvent> transactionListenerFactory(
            ConsumerFactory<String, TransactionReceivedEvent> transactionConsumerFactory,
            @Value("${frms.kafka.transactions.concurrency:12}") int concurrency
    ) {
        ConcurrentKafkaListenerContainerFactory<String, TransactionReceivedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(transactionConsumerFactory);
        factory.setBatchListener(true);
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        // If saving fails (e.g. database down), retry the batch every 2 s until it works:
        // a received transaction is never dropped.
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(2000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }
}
