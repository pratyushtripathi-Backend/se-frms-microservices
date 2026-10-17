package com.se.frms.transaction.service;

import com.se.frms.transaction.dto.TransactionReceivedEvent;
import com.se.frms.transaction.entity.TransactionMaster;
import com.se.frms.transaction.repository.TransactionRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import static org.mockito.Mockito.*;

class TransactionIngestConsumerTest {
    @Test void forwardsTimingWithoutChangingEvaluationInput() { check("123456", 123456L); }
    @Test void processesLegacyMessagesWithoutTimingHeader() { check(null, null); }
    @Test void malformedTimingDoesNotRejectBusinessMessage() { check("not-a-number", null); }

    private void check(String header, Long expected) {
        var repository = mock(TransactionRepository.class);
        var evaluation = mock(TransactionEvaluationService.class);
        var notifications = mock(DuplicateFraudNotificationService.class);
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.initialize();
        try {
            var entity = new TransactionMaster();
            entity.setId(UUID.randomUUID());
            entity.setTransactionData(Map.of("amount", 10));
            entity.setDuplicateTransaction(false);
            var event = TransactionReceivedEvent.from(entity, "test-request");
            var record = new ConsumerRecord<String, TransactionReceivedEvent>("transactions",0,0,"key",event);
            if (header != null) record.headers().add("frms-ingress-epoch-ms",header.getBytes(StandardCharsets.UTF_8));
            when(repository.findExistingIds(any())).thenReturn(List.of());
            var consumer = new TransactionIngestConsumer(repository,evaluation,notifications,executor);
            consumer.consume(List.of(record));
            verify(repository).saveAll(any());
            verify(evaluation).evaluateNow(entity.getId(),entity.getTransactionData(),expected);
            verifyNoInteractions(notifications);
        } finally { executor.shutdown(); }
    }
}
