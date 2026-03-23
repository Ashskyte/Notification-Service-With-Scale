# Redis Integration Plan - Notification System

## Why Redis? What Problems Does It Solve?

Our current system has specific bottlenecks that Redis directly addresses. This document maps every problem to the **exact file and line** in our codebase and explains what changes are needed.

---

## Current Problems Mapped to Code

| # | Problem | Where in Code | Impact |
|---|---------|---------------|--------|
| 1 | Every `sendNotification()` calls `userService.findUserById()` which hits DB | `NotificationService.java:44` | 1 DB query per send request |
| 2 | `sendBulkNotifications()` calls `findUserById()` in a loop for EVERY user | `NotificationService.java:94` | 1000 users = 1000 DB queries |
| 3 | Every Kafka consumer event does `notificationRepository.findByIdWithUser()` hitting DB | `NotificationKafkaConsumer.java:32, :52` | DB hit on every consumed message |
| 4 | No deduplication - Kafka at-least-once delivery can send same notification twice | `NotificationKafkaConsumer.consumeSendEvent()` | User gets duplicate Email/SMS |
| 5 | No rate limiting - all channels can be overwhelmed simultaneously | `NotificationProcessingService.processNotification()` | Provider throttling/ban |
| 6 | All 3 `@Scheduled` jobs run on EVERY app instance if scaled horizontally | `NotificationSchedulerService.java:27, :42, :73` | Duplicate scheduler execution |
| 7 | `getNotificationById()` and `getNotificationsByUserId()` always hit DB | `NotificationService.java:122, :128` | Unnecessary DB load on reads |

---

## How Redis Fixes Each Problem

### Problem 1 & 2: User Lookups Hit DB Every Time

**Current flow:**
```
sendNotification() --> userService.findUserById(userId) --> UserRepository.findById() --> DB Query
sendBulkNotifications() --> loop 1000 users --> 1000x findUserById() --> 1000 DB Queries
```

**With Redis:**
```
sendNotification() --> userService.findUserById(userId) --> Redis Cache HIT --> return cached User (0.5ms)
                                                       --> Redis Cache MISS --> DB Query --> store in Redis --> return
```

**Benefit:** For bulk send with 1000 users, if same user appears multiple times or is looked up again later, it is served from Redis (~0.5ms) instead of DB (~5-10ms). Even unique users get cached for subsequent Kafka consumer lookups.

---

### Problem 3: Kafka Consumer DB Lookups

**Current flow (NotificationKafkaConsumer.java:32):**
```java
Notification notification = notificationRepository.findByIdWithUser(event.getNotificationId())  // DB hit every time
```

**With Redis:** The notification was JUST saved to DB by `NotificationService.sendNotification()` moments ago. Caching the notification object after save means the Kafka consumer reads it from Redis instead of DB.

---

### Problem 4: No Deduplication (Duplicate Sends)

**Current flow:** Kafka guarantees at-least-once delivery. If the consumer crashes AFTER calling `handler.send(notification)` but BEFORE the Kafka offset is committed, the message is redelivered. The notification is sent AGAIN.

**With Redis:**
```
consumeSendEvent(event)
  --> Redis: SET NX "dedup:send:{notificationId}" "1" EX 300
  --> Key already exists? --> SKIP (duplicate)
  --> Key set successfully? --> PROCEED with processing
```

**Benefit:** User never receives duplicate Email/SMS. Critical when using real paid providers (Twilio, SES).

---

### Problem 5: No Rate Limiting

**Current flow (NotificationProcessingService.java:40-41):**
```java
NotificationChannelHandler handler = channelResolverService.resolve(notification.getChannel());
handler.send(notification);  // No throttle - sends as fast as possible
```

If 10,000 notifications arrive at once, all 10,000 hit the email provider simultaneously.

**With Redis (Sliding Window Rate Limiter):**
```
processNotification(notification)
  --> rateLimitService.isAllowed(EMAIL, userId)
  --> Redis: ZADD ratelimit:EMAIL:42 <timestamp> <uuid>
  --> Redis: ZCOUNT ratelimit:EMAIL:42 <windowStart> <now>
  --> count > 50/min? --> RATE_LIMITED, schedule for later
  --> count <= 50/min? --> PROCEED with send
```

**Benefit:** Protects against provider API bans, controls cost, prevents user spam.

---

### Problem 6: Duplicate Scheduler Execution

**Current flow:** `NotificationSchedulerService` has 3 `@Scheduled` methods:
- `processScheduledNotifications()` - runs every 30s
- `processRecurringNotifications()` - runs every 60s
- `retryFailedNotifications()` - runs every 10s

If we scale to 5 app instances, ALL 5 run these jobs simultaneously = 5x duplicate processing.

**With Redis (Distributed Lock):**
```
processScheduledNotifications()
  --> Redis: SET NX "lock:scheduler:scheduled" "instance-1" EX 25
  --> Lock acquired? --> Run the job
  --> Lock exists? --> Skip, another instance is handling it
```

**Benefit:** Only ONE instance runs each scheduled job at a time, even with 10 instances.

---

### Problem 7: Read Endpoints Always Hit DB

**Current flow:**
```
GET /api/notifications/123 --> notificationRepository.findById(123) --> DB Query every time
GET /api/notifications/user/42 --> notificationRepository.findByUserId(42, pageable) --> DB Query every time
```

**With Redis:**
```
GET /api/notifications/123 --> Redis Cache HIT --> return cached response (0.5ms)
                           --> Redis Cache MISS --> DB Query --> cache result --> return
```

**Benefit:** Read-heavy endpoints (dashboards, status checks) are served from Redis, DB is only hit on cache misses.

---

## Detailed Change List

### Change 1: Add Maven Dependencies

**File:** `pom.xml`

**What to add:**
```xml
<!-- Redis -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>

<!-- Spring Cache Abstraction -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
</dependency>
```

**Why both?**
- `spring-boot-starter-data-redis` provides `RedisTemplate`, `StringRedisTemplate`, Lettuce client
- `spring-boot-starter-cache` provides `@Cacheable`, `@CacheEvict`, `@CachePut` annotations with Redis as backend

---

### Change 2: Add Redis Properties

**File:** `application.properties`

**What to add:**
```properties
# ============ Redis Configuration ============
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.timeout=2000ms
spring.data.redis.lettuce.pool.max-active=16
spring.data.redis.lettuce.pool.max-idle=8
spring.data.redis.lettuce.pool.min-idle=2

# ============ Spring Cache (backed by Redis) ============
spring.cache.type=redis
spring.cache.redis.time-to-live=300000
spring.cache.redis.key-prefix=notif:
spring.cache.redis.use-key-prefix=true

# ============ Custom Redis Properties ============
notification.dedup.ttl-seconds=300
notification.ratelimit.email.max-per-minute=50
notification.ratelimit.sms.max-per-minute=20
notification.ratelimit.push.max-per-minute=100
```

---

### Change 3: Create RedisConfig.java

**New file:** `config/RedisConfig.java`

**Purpose:**
- Configure `RedisTemplate<String, Object>` with Jackson JSON serialization (default is JDK serialization which is unreadable)
- Configure `RedisCacheManager` with different TTLs per cache name:
  - `users` cache: 10 min TTL (users rarely change)
  - `notifications` cache: 2 min TTL (status changes frequently)
  - `user-notifications` cache: 60 sec TTL (paginated results go stale quickly)
- Add `@EnableCaching` annotation

**Why custom config?** Default `RedisTemplate` uses JDK serialization. Stored values look like garbage in Redis CLI. JSON serialization makes values readable, debuggable, and cross-language compatible.

---

### Change 4: Cache User Lookups in UserService

**File:** `service/UserService.java`

| Method | Annotation to Add | Behavior |
|--------|-------------------|----------|
| `findUserById(Long id)` | `@Cacheable(value = "users", key = "#id")` | First call hits DB, subsequent calls return from Redis |
| `getUserById(Long id)` | `@Cacheable(value = "users", key = "#id")` | Same - serves GET /api/users/{id} from cache |
| `registerUser(...)` | `@CachePut(value = "users", key = "#result.id")` on the return | Cache the newly created user immediately |
| `updateChannelPreferences(...)` | `@CacheEvict(value = "users", key = "#userId")` | Evict stale user from cache when preferences change |

**Impact on current code:**
- `NotificationService.sendNotification()` line 44: `userService.findUserById(request.getUserId())` now hits Redis instead of DB
- `NotificationService.sendBulkNotifications()` line 94: Loop of `findUserById()` calls now cached - massive improvement for repeated user IDs
- `NotificationKafkaConsumer` line 32: `findByIdWithUser()` fetches user via JOIN, but user portion is already warm in cache

---

### Change 5: Create DeduplicationService.java

**New file:** `service/DeduplicationService.java`

**Methods:**
```
boolean isDuplicate(String deduplicationKey)
  - Uses: redisTemplate.opsForValue().setIfAbsent(key, "1", ttl, TimeUnit.SECONDS)
  - Returns TRUE if key already existed (= duplicate)
  - Returns FALSE if key was newly set (= first time, proceed)
```

**Key format examples:**
- Send event: `dedup:send:{notificationId}` (e.g., `dedup:send:42`)
- Retry event: `dedup:retry:{notificationId}:{retryCount}` (e.g., `dedup:retry:42:2`)

**Why TTL?** Keys auto-expire after 5 minutes. We don't need to track dedup forever - just long enough to survive a Kafka redelivery window.

---

### Change 6: Integrate Dedup in Kafka Consumer

**File:** `kafka/NotificationKafkaConsumer.java`

**Changes to `consumeSendEvent()` (line 28-38):**
- Inject `DeduplicationService`
- Before `processingService.processNotification(notification)`, check:
```java
if (deduplicationService.isDuplicate("send:" + event.getNotificationId())) {
    log.warn("Duplicate SEND event for notification [{}] - skipping", event.getNotificationId());
    return;
}
```

**Changes to `consumeRetryEvent()` (line 48-58):**
- Same pattern with key: `"retry:" + event.getNotificationId()`

**Impact:** Prevents duplicate Email/SMS delivery when Kafka redelivers messages after consumer crash.

---

### Change 7: Create RateLimitService.java

**New file:** `service/RateLimitService.java`

**Method:**
```
boolean isAllowed(NotificationChannel channel, Long userId)
```

**Algorithm: Redis Sliding Window**
```
Key: ratelimit:{CHANNEL}:{userId}  (e.g., ratelimit:EMAIL:42)
Type: Sorted Set (ZSET)

1. ZREMRANGEBYSCORE key 0 (now - windowSize)     // Remove expired entries
2. ZCARD key                                       // Count current entries in window
3. If count < maxPerMinute:
     ZADD key <timestamp> <uuid>                   // Add this request
     EXPIRE key <windowSize>                        // Set TTL for cleanup
     return true (ALLOWED)
4. Else:
     return false (RATE LIMITED)
```

**Configurable limits per channel (from application.properties):**
- EMAIL: 50 requests/minute
- SMS: 20 requests/minute
- PUSH_NOTIFICATION: 100 requests/minute

---

### Change 8: Integrate Rate Limiting in Processing Service

**File:** `service/NotificationProcessingService.java`

**Change in `processNotification()` method (before line 40):**
- Inject `RateLimitService`
- Before `handler.send(notification)`, add:
```java
if (!rateLimitService.isAllowed(notification.getChannel(), notification.getUser().getId())) {
    notification.setStatus(NotificationStatus.RATE_LIMITED);
    notificationRepository.save(notification);
    retryService.scheduleRetry(notification);  // Retry later when rate limit resets
    return;
}
```

**File:** `model/enums/NotificationStatus.java`
- Add new enum value: `RATE_LIMITED`

---

### Change 9: Create DistributedLockService.java

**New file:** `service/DistributedLockService.java`

**Methods:**
```
boolean tryLock(String lockName, long ttlSeconds)
  - Uses: redisTemplate.opsForValue().setIfAbsent("lock:" + lockName, instanceId, ttl)
  - Returns TRUE if lock acquired (we are the leader)
  - Returns FALSE if lock already held by another instance

void releaseLock(String lockName)
  - Uses Lua script to atomically check-and-delete:
    if redis.call("get", key) == instanceId then redis.call("del", key) end
  - Only releases if WE own the lock (prevents accidental release of another instance's lock)
```

**Why Lua script for release?** Without atomic check-and-delete, there is a race condition:
1. Instance A's lock TTL expires
2. Instance B acquires the lock
3. Instance A tries to release -> accidentally deletes Instance B's lock

---

### Change 10: Add Distributed Locks to Scheduler

**File:** `service/NotificationSchedulerService.java`

| Scheduled Method | Lock Name | Lock TTL | Why This TTL |
|-----------------|-----------|----------|--------------|
| `processScheduledNotifications()` (runs every 30s) | `scheduler:scheduled` | 25 seconds | Shorter than interval to avoid blocking next run |
| `processRecurringNotifications()` (runs every 60s) | `scheduler:recurring` | 55 seconds | Same principle |
| `retryFailedNotifications()` (runs every 10s) | `scheduler:retry` | 8 seconds | Same principle |

**Change pattern for each method:**
```java
@Scheduled(fixedRate = 30000)
public void processScheduledNotifications() {
    if (!lockService.tryLock("scheduler:scheduled", 25)) {
        log.debug("Another instance holds the scheduler lock - skipping");
        return;
    }
    try {
        // ...existing scheduling logic...
    } finally {
        lockService.releaseLock("scheduler:scheduled");
    }
}
```

---

### Change 11: Cache Notification Read Endpoints

**File:** `service/NotificationService.java`

| Method | Annotation | Cache Name | TTL |
|--------|-----------|------------|-----|
| `getNotificationById(Long id)` | `@Cacheable(value = "notifications", key = "#id")` | notifications | 2 min |
| `getNotificationsByUserId(...)` | `@Cacheable(value = "user-notifications", key = "#userId + ':' + #page + ':' + #size")` | user-notifications | 60 sec |

**File:** `service/NotificationProcessingService.java`

After notification status changes to SENT or FAILED, evict the stale cache:
- Use programmatic eviction: `cacheManager.getCache("notifications").evict(notification.getId())`

**Why short TTL on user-notifications?** Paginated notification lists change frequently as new notifications are created/sent. 60-second TTL is a good balance between freshness and DB load reduction.

---

### Change 12: Add Redis to Docker Compose

**File:** `docker-compose.yml`

**Add Redis service:**
```yaml
redis:
  image: redis:7-alpine
  container_name: redis
  ports:
    - "6379:6379"
  command: redis-server --maxmemory 256mb --maxmemory-policy allkeys-lru
  healthcheck:
    test: ["CMD", "redis-cli", "ping"]
    interval: 10s
    timeout: 5s
    retries: 5
  networks:
    - notification-net
```

**Update notification-service:**
```yaml
notification-service:
  environment:
    SPRING_DATA_REDIS_HOST: redis    # <-- Add this
  depends_on:
    kafka:
      condition: service_healthy
    redis:                            # <-- Add this
      condition: service_healthy
```

**Why `allkeys-lru`?** When Redis reaches 256MB memory limit, it evicts least-recently-used keys automatically. This prevents Redis from running out of memory while keeping the hottest cached data available.

---

### Change 13: Health Check (Free - No Code Change)

Spring Boot auto-configures a Redis health indicator when `spring-boot-starter-data-redis` is on the classpath. Since we already have:
```properties
management.endpoint.health.show-details=always
```

The `/actuator/health` endpoint will automatically show Redis status:
```json
{
  "status": "UP",
  "components": {
    "redis": {
      "status": "UP",
      "details": { "version": "7.2.4" }
    }
  }
}
```

---

## Summary of All File Changes

| File | Type | What Changes |
|------|------|-------------|
| `pom.xml` | **Modified** | Add `spring-boot-starter-data-redis`, `spring-boot-starter-cache` |
| `application.properties` | **Modified** | Add Redis host/port/pool, cache TTLs, dedup TTL, rate limit configs |
| `config/RedisConfig.java` | **NEW** | RedisTemplate with JSON serializer, RedisCacheManager with per-cache TTLs, @EnableCaching |
| `service/DeduplicationService.java` | **NEW** | Redis SET NX based idempotency check |
| `service/RateLimitService.java` | **NEW** | Redis ZSET sliding window rate limiter per channel |
| `service/DistributedLockService.java` | **NEW** | Redis SET NX distributed lock with Lua script release |
| `service/UserService.java` | **Modified** | Add @Cacheable on findUserById/getUserById, @CachePut on register, @CacheEvict on update |
| `service/NotificationService.java` | **Modified** | Add @Cacheable on getNotificationById and getNotificationsByUserId |
| `service/NotificationProcessingService.java` | **Modified** | Add rate limit check before send, cache eviction after status change |
| `service/NotificationSchedulerService.java` | **Modified** | Wrap all 3 @Scheduled methods with distributed lock acquire/release |
| `kafka/NotificationKafkaConsumer.java` | **Modified** | Add dedup check at start of consumeSendEvent and consumeRetryEvent |
| `model/enums/NotificationStatus.java` | **Modified** | Add `RATE_LIMITED` enum value |
| `docker-compose.yml` | **Modified** | Add Redis container, add redis dependency to notification-service |

**New files: 4** | **Modified files: 9** | **Total: 13 files**

---

## Performance Impact Estimate

| Operation | Current (DB only) | With Redis | Improvement |
|-----------|-------------------|------------|-------------|
| User lookup `findUserById()` | ~5-10ms (DB query) | ~0.5ms (Redis GET) | **10-20x faster** |
| Notification fetch `findByIdWithUser()` | ~5-15ms (DB JOIN) | ~0.5ms (cache hit) | **10-30x faster** |
| Dedup check | N/A (not implemented) | ~0.3ms (SET NX) | **Prevents duplicate sends** |
| Rate limit check | N/A (not implemented) | ~0.5ms (ZADD + ZCOUNT) | **Prevents provider bans** |
| Distributed lock acquire | N/A (not implemented) | ~0.3ms (SET NX) | **Prevents duplicate jobs** |
| Bulk send 1000 users | ~5-10s (1000 DB calls) | ~0.5-1s (cache hits) | **5-10x faster** |
| GET /notifications/{id} | ~5ms (DB) | ~0.5ms (cache hit) | **10x faster** |

---

## Redis Memory Usage Estimate

| Cache | Key Example | Approx Size per Entry | Count | Total |
|-------|-------------|----------------------|-------|-------|
| Users | `notif:users::42` | ~500 bytes | 10,000 users | ~5 MB |
| Notifications | `notif:notifications::1001` | ~1 KB | 50,000 cached | ~50 MB |
| Dedup keys | `dedup:send:1001` | ~50 bytes | 100,000 (with TTL) | ~5 MB |
| Rate limit ZSETs | `ratelimit:EMAIL:42` | ~200 bytes | 10,000 active users | ~2 MB |
| Locks | `lock:scheduler:scheduled` | ~50 bytes | 3 locks | ~150 bytes |
| **Total** | | | | **~62 MB** |

With `maxmemory 256mb` and `allkeys-lru` eviction policy, we have plenty of headroom.

---

## Execution Order (Recommended)

Implement these changes in this order to get incremental value:

| Step | Change | Risk | Value |
|------|--------|------|-------|
| 1 | Add dependencies + Redis config + docker-compose | Low | Foundation for everything |
| 2 | User caching (UserService) | Low | Immediate bulk send improvement |
| 3 | Distributed locks (Scheduler) | Low | Safe to scale to multiple instances |
| 4 | Deduplication (Kafka Consumer) | Low | Prevents duplicate sends |
| 5 | Rate limiting (Processing Service) | Medium | Needs new enum + retry logic change |
| 6 | Notification caching (read endpoints) | Low | Reduces DB load on reads |
