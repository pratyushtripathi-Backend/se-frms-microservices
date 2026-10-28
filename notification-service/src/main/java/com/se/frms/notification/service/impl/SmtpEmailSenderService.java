package com.se.frms.notification.service.impl;

import com.se.frms.notification.service.EmailSenderService;
import jakarta.annotation.PreDestroy;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import com.se.frms.notification.exception.EmailProviderBlockedException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMailMessage;
import org.springframework.stereotype.Service;

/**
 * Sends one alert email. Same message as before (SimpleMailMessage: to, subject, text;
 * converted to MIME exactly the way JavaMailSenderImpl does), same SMTP settings
 * (spring.mail.*), same exceptions on failure.
 *
 * Faster: before, every email opened a new SMTP connection (TCP + STARTTLS + login,
 * ~1-1.5 s with Gmail) and closed it again. Now each email-delivery thread keeps its
 * SMTP connection open and reuses it for the next email:
 *  - a connection idle for more than CHECK_AFTER_IDLE_MS is checked (NOOP) first and
 *    reopened if the server closed it;
 *  - connections idle for more than CLOSE_AFTER_IDLE_MS are closed in the background;
 *  - if sending on a reused connection fails (e.g. the server dropped it), the email
 *    is sent once more on a fresh connection; invalid-address errors are not retried.
 *    If that also fails the error goes to the caller as before (FAILED + retry later).
 */
@Service
@Slf4j
public class SmtpEmailSenderService implements EmailSenderService {

    private static final long CHECK_AFTER_IDLE_MS = 5_000L;
    private static final long CLOSE_AFTER_IDLE_MS = 30_000L;

    private final JavaMailSender mailSender;

    /** One SMTP connection per email-delivery thread. */
    private final ThreadLocal<PooledConnection> connection = ThreadLocal.withInitial(this::newPooledConnection);
    private final Set<PooledConnection> allConnections = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService idleCloser = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "smtp-idle-closer");
        thread.setDaemon(true);
        return thread;
    });

    public SmtpEmailSenderService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
        idleCloser.scheduleWithFixedDelay(this::closeIdleConnections, 15, 15, TimeUnit.SECONDS);
    }

    @Override
    public void send(String recipient, String subject, String message) {
        SimpleMailMessage email = new SimpleMailMessage();
        email.setTo(recipient);
        email.setSubject(subject);
        email.setText(message);

        if (!(mailSender instanceof JavaMailSenderImpl sender)) {
            mailSender.send(email); // not the standard SMTP sender: send exactly as before
            return;
        }

        MimeMessage mimeMessage = toMimeMessage(sender, email);
        PooledConnection pooled = connection.get();
        synchronized (pooled) {
            pooled.inUse = true;
            try {
                boolean reused = pooled.transport != null;
                try {
                    sendOn(sender, pooled, mimeMessage);
                } catch (MessagingException firstFailure) {
                    closeQuietly(pooled);
                    // A provider block / quota error is not a dropped connection: sending
                    // again on a new connection would only be one more login attempt.
                    if (!reused || firstFailure instanceof SendFailedException
                            || EmailProviderBlockedException.classify(firstFailure) != null) {
                        throw firstFailure;
                    }
                    log.info("Reused SMTP connection failed ({}); sending again on a new connection",
                            firstFailure.getMessage());
                    sendOn(sender, pooled, mimeMessage);
                }
            } catch (AuthenticationFailedException ex) {
                closeQuietly(pooled);
                // Login refused (e.g. "454 Too many login attempts"): the provider is blocking us,
                // not this email - see EmailProviderBlockedException.
                throw new EmailProviderBlockedException(EmailProviderBlockedException.Kind.LOGIN, ex);
            } catch (MessagingException ex) {
                closeQuietly(pooled);
                EmailProviderBlockedException.Kind blockKind = EmailProviderBlockedException.classify(ex);
                if (blockKind != null) {
                    throw new EmailProviderBlockedException(blockKind, ex);
                }
                Map<Object, Exception> failed = new LinkedHashMap<>();
                failed.put(email, ex);
                throw new MailSendException(failed);
            } finally {
                pooled.lastUsedNanos = System.nanoTime();
                pooled.inUse = false;
            }
        }
    }

    private void sendOn(JavaMailSenderImpl sender, PooledConnection pooled, MimeMessage mimeMessage)
            throws MessagingException {
        Transport transport = connectedTransport(sender, pooled);
        Address[] addresses = mimeMessage.getAllRecipients();
        transport.sendMessage(mimeMessage, addresses != null ? addresses : new Address[0]);
    }

    /** Same conversion and headers as JavaMailSenderImpl.send(SimpleMailMessage). */
    private MimeMessage toMimeMessage(JavaMailSenderImpl sender, SimpleMailMessage email) {
        try {
            MimeMessage mimeMessage = sender.createMimeMessage();
            email.copyTo(new MimeMailMessage(mimeMessage));
            if (mimeMessage.getSentDate() == null) {
                mimeMessage.setSentDate(new Date());
            }
            String messageId = mimeMessage.getMessageID();
            mimeMessage.saveChanges();
            if (messageId != null) {
                mimeMessage.setHeader("Message-ID", messageId);
            }
            return mimeMessage;
        } catch (MessagingException ex) {
            throw new MailSendException("Could not prepare email message", ex);
        }
    }

    private Transport connectedTransport(JavaMailSenderImpl sender, PooledConnection pooled) throws MessagingException {
        if (pooled.transport != null) {
            long idleMs = (System.nanoTime() - pooled.lastUsedNanos) / 1_000_000;
            // Used within the last few seconds: reuse directly (no extra round trip).
            // Idle longer: check it is still open (NOOP) before reusing it.
            boolean usable = idleMs < CHECK_AFTER_IDLE_MS || stillOpen(pooled.transport);
            if (usable) {
                return pooled.transport;
            }
            closeQuietly(pooled);
        }
        Transport transport = sender.getSession().getTransport(
                sender.getProtocol() != null ? sender.getProtocol() : "smtp");
        String username = sender.getUsername();
        String password = sender.getPassword();
        if ("".equals(username)) { // same handling as JavaMailSenderImpl.connectTransport()
            username = null;
            if ("".equals(password)) {
                password = null;
            }
        }
        transport.connect(sender.getHost(), sender.getPort(), username, password);
        pooled.transport = transport;
        return transport;
    }

    /** isConnected() on an SMTP transport sends a NOOP, so a closed connection is detected. */
    private boolean stillOpen(Transport transport) {
        try {
            return transport.isConnected();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private PooledConnection newPooledConnection() {
        PooledConnection pooled = new PooledConnection();
        allConnections.add(pooled);
        return pooled;
    }

    private void closeIdleConnections() {
        long now = System.nanoTime();
        for (PooledConnection pooled : allConnections) {
            synchronized (pooled) {
                if (!pooled.inUse && pooled.transport != null
                        && (now - pooled.lastUsedNanos) / 1_000_000 > CLOSE_AFTER_IDLE_MS) {
                    closeQuietly(pooled);
                }
            }
        }
    }

    private void closeQuietly(PooledConnection pooled) {
        Transport transport = pooled.transport;
        pooled.transport = null;
        if (transport != null) {
            try {
                transport.close();
            } catch (MessagingException | RuntimeException ignored) {
                // already closed / broken - nothing to do
            }
        }
    }

    @PreDestroy
    void shutdown() {
        idleCloser.shutdownNow();
        for (PooledConnection pooled : allConnections) {
            synchronized (pooled) {
                closeQuietly(pooled);
            }
        }
    }

    private static final class PooledConnection {
        private Transport transport;
        private long lastUsedNanos = System.nanoTime();
        private boolean inUse;
    }
}
