package com.notification.service;

import com.notification.model.enums.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
public class RateLimitService {

    private final StringRedisTemplate redisTemplate;

    private static final String RATE_LIMIT_PREFIX = "ratelimit:";
    private static final long WINDOW_SIZE_MS = 60_000; // 1 minute sliding window

    private final int emailMaxPerMinute;
    private final int smsMaxPerMinute;
    private final int pushMaxPerMinute;

    public RateLimitService(
            StringRedisTemplate redisTemplate,
            @Value("${notification.ratelimit.email.max-per-minute:50}") int emailMaxPerMinute,
            @Value("${notification.ratelimit.sms.max-per-minute:20}") int smsMaxPerMinute,
            @Value("${notification.ratelimit.push.max-per-minute:100}") int pushMaxPerMinute) {
        this.redisTemplate = redisTemplate;
        this.emailMaxPerMinute = emailMaxPerMinute;
        this.smsMaxPerMinute = smsMaxPerMinute;
        this.pushMaxPerMinute = pushMaxPerMinute;
    }

    /**
     * Check if a notification is allowed under the rate limit for the given channel and user.
     * Uses a Redis Sorted Set (ZSET) sliding window algorithm.
     *
     * @param channel the notification channel (EMAIL, SMS, PUSH_NOTIFICATION)
     * @param userId the target user ID
     * @return true if allowed, false if rate limited
     */
    public boolean isAllowed(NotificationChannel channel, Long userId) {
        String key = RATE_LIMIT_PREFIX + channel.name() + ":" + userId;
        long now = System.currentTimeMillis();
        long windowStart = now - WINDOW_SIZE_MS;
        int maxAllowed = getMaxPerMinute(channel);

        ZSetOperations<String, String> zSetOps = redisTemplate.opsForZSet();

        // 1. Remove expired entries outside the sliding window
        zSetOps.removeRangeByScore(key, 0, windowStart);

        // 2. Count current entries in the window
        Long currentCount = zSetOps.zCard(key);
        if (currentCount == null) {
            currentCount = 0L;
        }

        // 3. Check if under the limit
        if (currentCount < maxAllowed) {
            // Add this request with timestamp as score and unique member
            zSetOps.add(key, UUID.randomUUID().toString(), now);
            // Set TTL on the key for automatic cleanup
            redisTemplate.expire(key, Duration.ofMillis(WINDOW_SIZE_MS + 1000));
            return true;
        } else {
            log.warn("Rate limit exceeded for channel {} and user {}: {}/{} per minute",
                    channel, userId, currentCount, maxAllowed);
            return false;
        }
    }

    private int getMaxPerMinute(NotificationChannel channel) {
        return switch (channel) {
            case EMAIL -> emailMaxPerMinute;
            case SMS -> smsMaxPerMinute;
            case PUSH_NOTIFICATION -> pushMaxPerMinute;
        };
    }
}
