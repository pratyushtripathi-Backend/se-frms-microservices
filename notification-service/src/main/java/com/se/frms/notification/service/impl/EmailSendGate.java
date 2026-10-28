package com.se.frms.notification.service.impl;

import com.se.frms.notification.exception.EmailProviderBlockedException;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Decides whether an email may be sent to the email provider right now. EMAIL ONLY - the
 * SMS channel does not use it.
 *
 * Why: when the provider (Gmail / SMTP relay) blocks the login or the daily quota is
 * used up, every further send is one more failed login attempt, which makes the block
 * longer, and the old retries (2 s, 5 s, 30 s) ran out within a minute so the emails
 * were lost. With the gate, a block is noticed once and sending pauses; the emails wait
 * (as FAILED "[WAITING]" entries in the existing notification JSON) and
 * NotificationServiceImpl.recoverWaitingEmails() sends them when the gate allows again.
 *
 * Three independent checks, in this order:
 *  1. Circuit breaker: after a provider block, nothing is sent for the pause time. After
 *     the pause exactly ONE email is let through as a probe; if it is accepted sending
 *     resumes, if it is blocked again the pause starts over. (So 12 threads never all
 *     log in against a provider that just refused us.)
 *  2. Optional pacing: notification.email.gate.max-per-minute (0 = off).
 *  3. Optional daily budget: notification.email.gate.daily-budget (0 = off), counted in
 *     Redis so it survives a restart; a fixed 24 h window from the first email. Redis
 *     problems never stop emails (the check is skipped).
 *
 * The cost of a decision is a short synchronized block (plus one Redis INCR only when a
 * daily budget is configured) on the email delivery thread - never on the Kafka consumer.
 */
@Component
@Slf4j
public class EmailSendGate {

    public enum Decision {
        /** Send now. */
        ALLOW,
        /** Send now - this email is the single probe after a provider block. */
        PROBE,
        DENY_BLOCKED,
        DENY_RATE,
        DENY_BUDGET;

        public boolean allowed() {
            return this == ALLOW || this == PROBE;
        }
    }

    private enum State { CLOSED, OPEN, HALF_OPEN }

    private static final String BUDGET_KEY = "frms:notification:email-daily-budget";
    // A probe that never reports back (should not happen) must not block sending forever.
    private static final long PROBE_TIMEOUT_NANOS = Duration.ofMinutes(2).toNanos();

    private final StringRedisTemplate redis;
    private final long loginPauseNanos;
    private final long quotaPauseNanos;
    private final int maxPerMinute;
    private final long dailyBudget;

    private State state = State.CLOSED;
    private long openUntilNanos;
    private long halfOpenSinceNanos;

    private final double bucketCapacity;
    private double tokens;
    private long lastRefillNanos = System.nanoTime();

    public EmailSendGate(
            StringRedisTemplate redis,
            @Value("${notification.email.gate.block-pause-minutes:15}") long blockPauseMinutes,
            @Value("${notification.email.gate.quota-pause-minutes:60}") long quotaPauseMinutes,
            @Value("${notification.email.gate.max-per-minute:0}") int maxPerMinute,
            @Value("${notification.email.gate.daily-budget:0}") long dailyBudget
    ) {
        this.redis = redis;
        this.loginPauseNanos = Duration.ofMinutes(Math.max(1, blockPauseMinutes)).toNanos();
        this.quotaPauseNanos = Duration.ofMinutes(Math.max(1, quotaPauseMinutes)).toNanos();
        this.maxPerMinute = Math.max(0, maxPerMinute);
        this.dailyBudget = Math.max(0, dailyBudget);
        // Allows about 10 seconds of burst, then the rate is smoothed to max-per-minute.
        this.bucketCapacity = Math.max(1, this.maxPerMinute / 6.0);
        this.tokens = this.bucketCapacity;
    }

    /** Asks for permission to send one email. Report the outcome with onSuccess / onProviderBlocked / release. */
    public Decision tryAcquire() {
        Decision decision;
        synchronized (this) {
            long now = System.nanoTime();
            if (state == State.HALF_OPEN && now - halfOpenSinceNanos > PROBE_TIMEOUT_NANOS) {
                state = State.OPEN;
                openUntilNanos = now;
            }
            if (state == State.HALF_OPEN) {
                return Decision.DENY_BLOCKED; // the probe is still in flight
            }
            if (state == State.OPEN && now - openUntilNanos < 0) {
                return Decision.DENY_BLOCKED;
            }
            if (maxPerMinute > 0 && !takeToken(now)) {
                return Decision.DENY_RATE;
            }
            if (state == State.OPEN) {
                state = State.HALF_OPEN;
                halfOpenSinceNanos = now;
                decision = Decision.PROBE;
            } else {
                decision = Decision.ALLOW;
            }
        }
        if (dailyBudget > 0 && !takeDailyBudget()) {
            release(decision);
            return Decision.DENY_BUDGET;
        }
        return decision;
    }

    /** True while the recovery job should not even try (provider blocked / probe in flight). */
    public synchronized boolean isPaused() {
        long now = System.nanoTime();
        if (state == State.HALF_OPEN) {
            return now - halfOpenSinceNanos <= PROBE_TIMEOUT_NANOS;
        }
        return state == State.OPEN && now - openUntilNanos < 0;
    }

    /** The provider accepted an email: sending is healthy again. */
    public synchronized void onSuccess() {
        if (state != State.CLOSED) {
            log.info("Email provider accepted mail again; email sending resumed");
        }
        state = State.CLOSED;
    }

    /** The provider refused (login blocked / quota / throttled): pause sending. */
    public synchronized void onProviderBlocked(EmailProviderBlockedException.Kind kind) {
        long now = System.nanoTime();
        long pause = kind == EmailProviderBlockedException.Kind.QUOTA ? quotaPauseNanos : loginPauseNanos;
        boolean alreadyPaused = state == State.OPEN && now - openUntilNanos < 0;
        long newUntil = now + pause;
        if (!alreadyPaused || newUntil - openUntilNanos > 0) {
            openUntilNanos = newUntil;
        }
        state = State.OPEN;
        if (!alreadyPaused) {
            log.warn("Email provider is blocking sends ({}); email sending paused for {} min. "
                            + "Emails are kept and sent automatically afterwards - nothing is dropped.",
                    kind, Duration.ofNanos(pause).toMinutes());
        }
    }

    /**
     * Gives back a PROBE permit that ended without a verdict (ordinary failure, nothing
     * to send, ...), so the next email can probe. Safe to call after any outcome.
     */
    public synchronized void release(Decision decision) {
        if (decision == Decision.PROBE && state == State.HALF_OPEN) {
            state = State.OPEN;
            openUntilNanos = System.nanoTime();
        }
    }

    /** Text stored as the failure reason of an email that has to wait ("[WAITING]" marker). */
    public static String waitingReason(Decision decision) {
        return switch (decision) {
            case DENY_RATE -> "email sending rate limit - will be sent automatically shortly";
            case DENY_BUDGET -> "daily email budget reached - will be sent automatically later";
            default -> "email provider is blocking sends - will be sent automatically later";
        };
    }

    private boolean takeToken(long now) {
        double refill = (now - lastRefillNanos) * (maxPerMinute / 60_000_000_000.0);
        tokens = Math.min(bucketCapacity, tokens + refill);
        lastRefillNanos = now;
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    private boolean takeDailyBudget() {
        try {
            Long used = redis.opsForValue().increment(BUDGET_KEY);
            if (used == null) {
                return true;
            }
            if (used == 1L) {
                redis.expire(BUDGET_KEY, Duration.ofHours(24));
            }
            if (used > dailyBudget) {
                redis.opsForValue().decrement(BUDGET_KEY);
                // Make sure the counter can never be left without an expiry (it would block forever).
                Long ttl = redis.getExpire(BUDGET_KEY);
                if (ttl != null && ttl < 0) {
                    redis.expire(BUDGET_KEY, Duration.ofHours(24));
                }
                return false;
            }
            return true;
        } catch (RuntimeException ex) {
            log.warn("Daily email budget check skipped (Redis unavailable): {}", ex.toString());
            return true;
        }
    }
}
