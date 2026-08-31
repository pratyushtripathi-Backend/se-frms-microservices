package com.se.frms.notification.service;

public interface EmailSenderService {
    void send(String recipient, String subject, String message);
}
