package com.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

@Slf4j
@Service
public class DistributedLockService {

    private final StringRedisTemplate redisTemplate;
    private final String instanceId;

    private static final String LOCK_PREFIX = "lock:";

    // Lua script: only delete the key if it holds our instanceId (safe release)
    private static final String RELEASE_LOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else " +
            "  return 0 " +
            "end";

    public DistributedLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.instanceId = UUID.randomUUID().toString();
        log.info("DistributedLockService initialized with instanceId: {}", instanceId);
    }

    /**
     * Try to acquire a distributed lock.
     *
     * @param lockName unique name for the lock
     * @param ttlSeconds lock auto-expires after this many seconds (prevents deadlocks)
     * @return true if lock acquired, false if another instance holds it
     */
    public boolean tryLock(String lockName, long ttlSeconds) {
        String key = LOCK_PREFIX + lockName;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, instanceId, Duration.ofSeconds(ttlSeconds));
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * Release a distributed lock. Only releases if WE own it (using Lua script for atomicity).
     *
     * @param lockName unique name for the lock
     */
    public void releaseLock(String lockName) {
        String key = LOCK_PREFIX + lockName;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(RELEASE_LOCK_SCRIPT, Long.class);
        Long result = redisTemplate.execute(script, Collections.singletonList(key), instanceId);
        if (result != null && result == 1) {
            log.debug("Released lock: {}", lockName);
        } else {
            log.debug("Lock {} was not held by this instance or already expired", lockName);
        }
    }
}
