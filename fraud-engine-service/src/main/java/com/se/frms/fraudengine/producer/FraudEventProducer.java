package com.se.frms.fraudengine.producer;
import com.se.frms.fraudengine.dto.FraudEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
@Component
@RequiredArgsConstructor
public class FraudEventProducer {
    private final KafkaTemplate<String, FraudEvent> kafkaTemplate;
    @Value("${frms.kafka.topic.fraud-events}")
    private String fraudEventsTopic;
    public void publish(FraudEvent event) {
        kafkaTemplate.send(fraudEventsTopic, event.transactionId().toString(), event);
    }
}
