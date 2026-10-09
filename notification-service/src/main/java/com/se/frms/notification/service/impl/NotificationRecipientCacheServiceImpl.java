package com.se.frms.notification.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.se.frms.notification.client.MonolithAdminClient;
import com.se.frms.notification.dto.AdminNotificationRecipient;
import com.se.frms.notification.service.NotificationRecipientCacheService;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationRecipientCacheServiceImpl implements NotificationRecipientCacheService {

    private final MonolithAdminClient monolithAdminClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${notification.recipients.redis-key}")
    private String redisKey;

    @Value("${notification.recipients.ttl-minutes:10}")
    private long ttlMinutes;

    // Short-lived in-memory copy of the Redis value, so a burst of fraud events
    // does not do a Redis GET + JSON parse per event. Kept short (default 5 s) so a
    // refresh done by another instance is still picked up almost immediately.
    @Value("${notification.recipients.local-cache-ms:5000}")
    private long localCacheMs;

    private record LocalSnapshot(List<AdminNotificationRecipient> recipients, long loadedAtNanos) {
    }

    private volatile LocalSnapshot localSnapshot;

    @Override
    public void refreshRecipients() {
        try {
            List<AdminNotificationRecipient> recipients = monolithAdminClient.fetchActiveAdminRecipients();
            stringRedisTemplate.opsForValue().set(
                    redisKey,
                    objectMapper.writeValueAsString(recipients),
                    Duration.ofMinutes(ttlMinutes)
            );
            localSnapshot = new LocalSnapshot(List.copyOf(recipients), System.nanoTime());
            log.info("Admin notification recipients refreshed in Redis, count={}", recipients.size());
        } catch (Exception ex) {
            log.warn("Admin notification recipient refresh failed; existing Redis cache will be used", ex);
        }
    }

    @Override
    public List<AdminNotificationRecipient> getCachedRecipients() {
        LocalSnapshot snapshot = localSnapshot;
        if (snapshot != null && System.nanoTime() - snapshot.loadedAtNanos() < localCacheMs * 1_000_000L) {
            return snapshot.recipients();
        }
        try {
            String cachedValue = stringRedisTemplate.opsForValue().get(redisKey);
            List<AdminNotificationRecipient> recipients = !StringUtils.hasText(cachedValue)
                    ? List.of()
                    : List.copyOf(objectMapper.readValue(cachedValue,
                            new TypeReference<List<AdminNotificationRecipient>>() { }));
            localSnapshot = new LocalSnapshot(recipients, System.nanoTime());
            return recipients;
        } catch (Exception ex) {
            log.warn("Unable to read admin notification recipients from Redis", ex);
            return List.of();
        }
    }
}
