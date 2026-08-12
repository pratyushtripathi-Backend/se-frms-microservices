package com.se.frms.notification.consumer;
import com.se.frms.notification.dto.FraudEvent;
import com.se.frms.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
@Component
@RequiredArgsConstructor
public class FraudEventConsumer {
    private final NotificationService notificationService;
    @KafkaListener(topics = "${frms.kafka.topic.fraud-events}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(FraudEvent event) {
        notificationService.handleFraudEvent(event);
    }
}
