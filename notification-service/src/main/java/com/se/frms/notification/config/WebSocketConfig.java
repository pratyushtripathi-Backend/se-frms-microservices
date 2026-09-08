package com.se.frms.notification.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Enables real-time push of DASHBOARD alerts to connected admin-dashboard browsers,
 * alongside (not instead of) the existing REST APIs. Nothing about the Kafka
 * consumption flow, DB persistence, or EMAIL/SMS dispatch changes because of this -
 * see NotificationServiceImpl.createIfAbsent(), which pushes to the "/topic/alerts"
 * destination configured here right after it already saves a DASHBOARD notification,
 * exactly as it did before this file existed.
 *
 * Frontend connects once (SockJS + STOMP client) to "/ws" and subscribes to
 * "/topic/alerts" to receive each new DASHBOARD alert the instant it happens,
 * instead of polling the existing GET /api/v1/notifications/dashboard/feed
 * endpoint repeatedly. That REST endpoint is unaffected and still works exactly as
 * before - useful for loading existing/historical alerts once when the dashboard
 * page first opens, before the live WebSocket feed takes over for new ones.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Simple in-memory broker is enough here: one notification-service instance,
        // one topic, no cross-instance fan-out requirement today.
        registry.enableSimpleBroker("/topic");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // No auth/security exists anywhere else in this service today (confirmed
        // earlier), so allowedOriginPatterns("*") matches the existing security
        // posture rather than introducing an inconsistency here.
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
