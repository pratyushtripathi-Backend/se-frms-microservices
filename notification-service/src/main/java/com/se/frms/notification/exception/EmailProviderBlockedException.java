package com.se.frms.notification.exception;

import java.util.Locale;
import org.springframework.mail.MailException;

/**
 * The email provider (Gmail, SMTP relay, ...) refuses to take mail right now for a reason
 * that has nothing to do with this one email: the login is blocked ("Too many login
 * attempts") or failed, the daily sending quota is used up, or sending is throttled.
 *
 * Retrying at once cannot help, and every immediate retry is another login attempt that
 * makes a login block last longer. So the email is NOT counted as a failed attempt: it
 * is kept and sent later, when EmailSendGate allows sending again (see
 * NotificationServiceImpl.deliverEmail / recoverWaitingEmails).
 */
public class EmailProviderBlockedException extends MailException {

    public enum Kind {
        /** Login blocked / failed, or sending throttled: wait a while and try again. */
        LOGIN,
        /** Daily sending quota used up: wait longer. */
        QUOTA
    }

    private final Kind kind;

    public EmailProviderBlockedException(Kind kind, Throwable cause) {
        super(describe(kind, cause), cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    /**
     * Returns which provider block an error represents, or null when it is an ordinary
     * failure of this one email (bad address, timeout, ...), which keeps the normal retries.
     */
    public static Kind classify(Throwable error) {
        int depth = 0;
        for (Throwable t = error; t != null && depth < 10; t = t.getCause(), depth++) {
            String text = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            if (text.contains("5.4.5")
                    || text.contains("daily user sending")
                    || text.contains("daily sending")
                    || text.contains("sending limit exceeded")
                    || text.contains("relay limit exceeded")) {
                return Kind.QUOTA;
            }
            if (text.contains("too many login attempts")
                    || text.contains("too many sign-in attempts")
                    || text.contains("4.7.28")
                    || text.contains("unusual rate")
                    || (text.contains("4.7.0") && text.contains("try again later"))) {
                return Kind.LOGIN;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }

    private static String describe(Kind kind, Throwable cause) {
        String reason = cause == null || cause.getMessage() == null ? "" : cause.getMessage().trim();
        int newline = reason.indexOf('\n');
        if (newline > 0) {
            reason = reason.substring(0, newline).trim();
        }
        if (reason.length() > 300) {
            reason = reason.substring(0, 300);
        }
        String what = kind == Kind.QUOTA
                ? "daily email sending limit reached"
                : "email provider login blocked or sending throttled";
        return reason.isEmpty() ? what : what + ": " + reason;
    }
}
