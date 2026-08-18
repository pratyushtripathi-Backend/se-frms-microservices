package com.se.frms.fraudengine.producer;

import com.se.frms.fraudengine.dto.FraudEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class FraudEventProducer {

    private final KafkaTemplate<String, FraudEvent> kafkaTemplate;

    @Value("${frms.kafka.topic.fraud-events}")
    private String fraudEventsTopic;

    public void publish(FraudEvent event) {
        log.info(
                "Publishing fraud event transactionId={}, decision={}, riskScore={}",
                event.transactionId(),
                event.fraudDecision(),
                event.totalRiskScore()
        );
        kafkaTemplate.send(fraudEventsTopic, event.transactionId().toString(), event);
    }
}
