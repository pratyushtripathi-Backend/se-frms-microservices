package com.se.frms.notification.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.se.frms.notification.dto.EmailTemplateContent;
import com.se.frms.notification.entity.NotificationTemplate;
import com.se.frms.notification.repository.NotificationTemplateRepository;
import com.se.frms.notification.service.NotificationTemplateCacheService;
import java.time.Duration;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationTemplateCacheServiceImpl implements NotificationTemplateCacheService {
    private static final String EMAIL = "EMAIL";

    private final NotificationTemplateRepository notificationTemplateRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${notification.templates.redis-key-prefix:frms:notification:template:}")
    private String redisKeyPrefix;

    @Value("${notification.templates.ttl-minutes:60}")
    private long ttlMinutes;

    @Override
    public void refreshEmailTemplates() {
        notificationTemplateRepository.findByNotificationType(EMAIL)
                .forEach(template -> {
                    if (Boolean.TRUE.equals(template.getStatus())) {
                        cacheTemplate(template);
                    } else {
                        evictEmailTemplate(template.getTemplateCode());
                    }
                });
        log.info("Active email notification templates refreshed in Redis");
    }

    @Override
    public void evictEmailTemplate(String templateCode) {
        try {
            stringRedisTemplate.delete(redisKey(templateCode));
        } catch (Exception ex) {
            log.warn("Unable to evict email template {} from Redis", templateCode, ex);
        }
    }

    @Override
    public EmailTemplateContent getEmailTemplate(String fraudDecision) {
        String templateCode = emailTemplateCode(fraudDecision);
        try {
            String cached = stringRedisTemplate.opsForValue().get(redisKey(templateCode));
            if (StringUtils.hasText(cached)) {
                return objectMapper.readValue(cached, EmailTemplateContent.class);
            }

            return notificationTemplateRepository.findByTemplateCodeAndStatusTrue(templateCode)
                    .map(template -> {
                        cacheTemplate(template);
                        return toContent(template);
                    })
                    .orElse(null);
        } catch (Exception ex) {
            log.warn("Unable to load email template {} from cache; code fallback will be used", templateCode, ex);
            return null;
        }
    }

    private void cacheTemplate(NotificationTemplate template) {
        try {
            stringRedisTemplate.opsForValue().set(
                    redisKey(template.getTemplateCode()),
                    objectMapper.writeValueAsString(toContent(template)),
                    Duration.ofMinutes(ttlMinutes)
            );
        } catch (Exception ex) {
            log.warn("Unable to cache email template {}", template.getTemplateCode(), ex);
        }
    }

    private EmailTemplateContent toContent(NotificationTemplate template) {
        return new EmailTemplateContent(template.getSubjectTemplate(), template.getBodyTemplate());
    }

    private String emailTemplateCode(String fraudDecision) {
        return "FRAUD_" + fraudDecision.trim().toUpperCase(Locale.ROOT) + "_EMAIL";
    }

    private String redisKey(String templateCode) {
        return redisKeyPrefix + templateCode;
    }
}
