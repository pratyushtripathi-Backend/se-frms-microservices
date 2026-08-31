package com.se.frms.notification.service;

public interface SmsSenderService {
    void send(String phoneNumber, String message, String templateId, String correlationId);
}
