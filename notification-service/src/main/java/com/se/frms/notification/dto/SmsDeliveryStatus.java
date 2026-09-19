package com.se.frms.notification.dto;

/**
 * Normalized outcome of an MSG24x7 MessageStatus lookup. "status" carries the raw
 * provider status string (e.g. "DELIVRD", "UNDELIV") for logging/debugging, while
 * delivered()/failed() give the caller a stable check independent of provider-specific
 * status codes.
 */
public record SmsDeliveryStatus(String status, String reason) {
    public boolean delivered() {
        return "DELIVRD".equalsIgnoreCase(status) || "DELIVERED".equalsIgnoreCase(status);
    }

    public boolean failed() {
        return "UNDELIV".equalsIgnoreCase(status)
                || "REJECTD".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status)
                || "EXPIRED".equalsIgnoreCase(status);
    }
}
