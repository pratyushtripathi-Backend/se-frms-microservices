package com.se.frms.notification.service;
import com.se.frms.notification.dto.FraudEvent;
public interface NotificationService { void handleFraudEvent(FraudEvent event); }
