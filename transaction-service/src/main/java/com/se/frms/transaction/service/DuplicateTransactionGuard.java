package com.se.frms.transaction.service;

import com.se.frms.transaction.entity.TransactionMaster;
import com.se.frms.transaction.repository.TransactionRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Duplicate (idempotent retry) check on externalTransactionId, done in Redis with one
 * atomic SET NX instead of a database query per POST.
 *
 * Key "frms:txn:ext:{externalTransactionId}" -> id of the FIRST transaction with that
 * externalTransactionId, kept for frms.duplicate-check.ttl-days (default 30). SET NX is
 * atomic, so two identical requests arriving at the same moment can never both be
 * treated as new (the old SELECT-then-INSERT could let both through).
 *
 * If Redis is not reachable, the old database check is used, so duplicates are still
 * detected (just slower). Duplicates older than the Redis period are caught by
 * TransactionIngestConsumer when the row is saved.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DuplicateTransactionGuard {

    private static final String KEY_PREFIX = "frms:txn:ext:";

    private final StringRedisTemplate redisTemplate;
    private final TransactionRepository transactionRepository;

    @Value("${frms.duplicate-check.ttl-days:30}")
    private long ttlDays;

    /**
     * Claims externalTransactionId for newTransactionId.
     *
     * @return empty if this is the first transaction with that externalTransactionId,
     *         otherwise the id of the original transaction.
     */
    public Optional<UUID> claim(String externalTransactionId, UUID newTransactionId) {
        if (!StringUtils.hasText(externalTransactionId)) {
            return Optional.empty();
        }
        String key = KEY_PREFIX + externalTransactionId;
        try {
            Boolean claimed = redisTemplate.opsForValue()
                    .setIfAbsent(key, newTransactionId.toString(), Duration.ofDays(ttlDays));
            if (Boolean.TRUE.equals(claimed)) {
                return Optional.empty();
            }
            String original = redisTemplate.opsForValue().get(key);
            if (original != null) {
                return Optional.of(UUID.fromString(original));
            }
            // Key expired between the two calls - treat as new and claim it.
            return claim(externalTransactionId, newTransactionId);
        } catch (RuntimeException ex) {
            log.warn("Redis duplicate check unavailable, using database check externalTransactionId={}, reason={}",
                    externalTransactionId, ex.getMessage());
            return transactionRepository.findFirstByExternalTransactionIdOrderByCreatedDateAsc(externalTransactionId)
                    .map(TransactionMaster::getId);
        }
    }

    /** Undoes claim() when the transaction could not be accepted (e.g. Kafka unavailable). */
    public void release(String externalTransactionId, UUID transactionId) {
        if (!StringUtils.hasText(externalTransactionId)) {
            return;
        }
        String key = KEY_PREFIX + externalTransactionId;
        try {
            if (transactionId.toString().equals(redisTemplate.opsForValue().get(key))) {
                redisTemplate.delete(key);
            }
        } catch (RuntimeException ex) {
            log.warn("Could not release duplicate-check key externalTransactionId={}, reason={}",
                    externalTransactionId, ex.getMessage());
        }
    }
}
