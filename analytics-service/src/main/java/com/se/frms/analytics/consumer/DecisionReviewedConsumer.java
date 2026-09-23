package com.se.frms.analytics.consumer;

import com.se.frms.analytics.dto.DecisionReviewedEvent;
import com.se.frms.analytics.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DecisionReviewedConsumer {

    private final AnalyticsService analyticsService;

    @KafkaListener(
            topics = "${frms.kafka.topic.decision-reviewed}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "decisionReviewedListenerContainerFactory"
    )
    public void consume(DecisionReviewedEvent event) {
        log.info(
                "Decision-reviewed event received transactionId={}, previousDecision={}, finalDecision={}",
                event.transactionId(),
                event.previousDecision(),
                event.finalDecision()
        );
        analyticsService.applyDecisionReview(event.transactionId(), event.finalDecision());
    }
}
