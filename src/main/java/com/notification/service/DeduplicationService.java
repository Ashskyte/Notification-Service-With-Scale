package com.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeduplicationService {

    private final StringRedisTemplate redisTemplate;

    private static final String DEDUP_PREFIX = "dedup:";

    @Value("${notification.dedup.ttl-seconds:300}")
    private long dedupTtlSeconds;

    /**
     * Dedup TTL for content-based (API-level) deduplication.
     * Default: 60 seconds. Tune via notification.dedup.content-ttl-seconds.
     */
    @Value("${notification.dedup.content-ttl-seconds:60}")
    private long contentDedupTtlSeconds;

    /**
     * Check if this event has already been processed.
     * Uses Redis SET NX (set-if-not-exists) with TTL for automatic cleanup.
     *
     * @param deduplicationKey unique key for the event (e.g., "send:42" or "retry:42:1")
     * @return true if this is a DUPLICATE (key already existed), false if first time (key was set)
     */
    public boolean isDuplicate(String deduplicationKey) {
        return isDuplicate(deduplicationKey, dedupTtlSeconds);
    }

    /**
     * Content-based deduplication check with the content-specific TTL window.
     * Use this for API-level dedup (same payload submitted twice by a client).
     */
    public boolean isContentDuplicate(String deduplicationKey) {
        return isDuplicate(deduplicationKey, contentDedupTtlSeconds);
    }

    private boolean isDuplicate(String deduplicationKey, long ttlSeconds) {
        String key = DEDUP_PREFIX + deduplicationKey;
        Boolean wasSet = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", Duration.ofSeconds(ttlSeconds));

        if (Boolean.TRUE.equals(wasSet)) {
            // Key was newly set - this is the FIRST time we see this event
            return false;
        } else {
            // Key already existed - this is a DUPLICATE
            log.warn("Duplicate event detected for key: {}", key);
            return true;
        }
    }
}
