package com.se.frms.notification.service;

import com.se.frms.notification.dto.SmsDeliveryStatus;

public interface SmsSenderService {
    /**
     * Sends the SMS and returns the provider's MessageId for this send, so the
     * caller can persist it and later confirm real carrier delivery via
     * checkDeliveryStatus(). Throws on failure, same as before.
     */
    String send(String phoneNumber, String message, String templateId, String correlationId);

    /**
     * Looks up the real delivery outcome for a previously sent message. This is a
     * best-effort read against the provider's status API - implementations should
     * not throw for a "still pending" result, only for a genuine call failure.
     */
    SmsDeliveryStatus checkDeliveryStatus(String messageId);
}
