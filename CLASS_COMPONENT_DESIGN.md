# Class & Component Design

> Notification System — Separation of Concerns, Channel Abstractions, and Extensibility

---

## 1. High-Level Component Map

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              REST API Layer                                     │
│          NotificationController          UserController                         │
└───────────────────────┬─────────────────────────┬───────────────────────────────┘
                        │                         │
                        ▼                         ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                         Notification Generation Layer                            │
│                                                                                  │
│   NotificationService  ──────────────────────►  DeduplicationService            │
│   (orchestrates send,                           (Redis SET NX — rejects          │
│    schedule, bulk)                               duplicate API requests)         │
└──────────────┬───────────────────────────────────────────────────────────────────┘
               │ publishes NotificationEvent
               ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                              Kafka Messaging Layer                               │
│                                                                                  │
│   NotificationKafkaProducer  ──topic──►  NotificationKafkaConsumer              │
│   (notification-send,                   (at-least-once, dedup-guarded,          │
│    notification-retry)                   triggers processing or retry)           │
└──────────────┬───────────────────────────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                          Channel Delivery Layer                                  │
│                                                                                  │
│   NotificationProcessingService                                                  │
│   ├── RateLimitService  (Redis sliding window — per user per channel)            │
│   ├── ChannelResolverService  (Strategy registry — maps channel → handler)       │
│   └── NotificationChannelHandler  «interface»                                   │
│         ├── EmailChannelHandler                                                  │
│         ├── SmsChannelHandler                                                    │
│         └── PushNotificationChannelHandler                                       │
└──────────────┬───────────────────────────────────────────────────────────────────┘
               │ on failure
               ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                              Retry Layer                                         │
│                                                                                  │
│   RetryService  (exponential backoff — schedules RETRY status + nextRetryAt)    │
│   NotificationSchedulerService  (polls DB every 30s — picks up RETRY/SCHEDULED) │
│   DistributedLockService  (Redis SET NX — prevents duplicate scheduler runs     │
│                             across multiple app instances)                       │
└──────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Layer-by-Layer Class Design

---

### 2.1 Notification Generation Layer

**Responsibility:** Accept API requests, validate input, apply content-based deduplication, persist the notification, and publish an event to Kafka. Does **not** send anything itself.

```
NotificationService
├── sendNotification(SendNotificationRequest) → NotificationResponse
│     1. buildContentDeduplicationKey(request)  → SHA-256 hash
│     2. deduplicationService.isContentDuplicate(key)  → 409 if true
│     3. userService.findUserById(userId)
│     4. Notification.builder()... .status(PENDING)
│     5. notificationRepository.save(notification)
│     6. kafkaProducer.sendNotificationEvent(NotificationEvent.ofSend(id))
│
├── sendBulkNotifications(BulkNotificationRequest) → List<NotificationResponse>
│     Iterates userIds in configurable batches, calls steps 3-6 per user
│
├── getNotificationById(id)       → @Cacheable("notifications")
├── getNotificationsByUserId(...) → @Cacheable("user-notifications")
└── getNotificationsByUserAndStatus(...)

Dependencies:
  NotificationRepository   — persistence
  UserService              — user lookup
  NotificationKafkaProducer— event publishing
  DeduplicationService     — content dedup guard
```

```
DeduplicationService
├── isContentDuplicate(key)  → Redis SET NX with content-ttl-seconds (default 60s)
│     key = "dedup:content:{SHA-256(userId+channel+title+message)}"
│     → true  = DUPLICATE  (key already existed)
│     → false = FIRST TIME (key was newly set)
│
└── isDuplicate(key)         → Redis SET NX with ttl-seconds (default 300s)
      key = "dedup:send:{notificationId}"  or  "dedup:retry:{notificationId}"
      Used by Kafka consumer to guard against at-least-once redelivery

Redis key namespace:  dedup:*
TTL configuration:
  notification.dedup.content-ttl-seconds = 60   (API-level)
  notification.dedup.ttl-seconds          = 300  (Kafka-level)
```

---

### 2.2 Channel Delivery Layer

**Responsibility:** Given a persisted notification, choose the correct channel handler, enforce rate limits, dispatch the message, and update status. Completely decoupled from how the notification was created.

#### 2.2.1 Channel Handler Interface (Strategy Pattern)

```
«interface» NotificationChannelHandler
├── send(Notification notification) : void
│     Throws RuntimeException on delivery failure
│     (caught by NotificationProcessingService → triggers retry)
│
├── getChannel() : NotificationChannel
│     Returns the enum value this handler owns (EMAIL / SMS / PUSH_NOTIFICATION)
│
└── supports(NotificationChannel channel) : boolean
      Convenience predicate; used for validation

Implementations (all annotated @Component — auto-discovered by Spring):
┌─────────────────────────────────┬──────────────────────────────────────────────┐
│ Class                           │ Channel          │ Production target          │
├─────────────────────────────────┼──────────────────┼────────────────────────────┤
│ EmailChannelHandler             │ EMAIL            │ AWS SES / SendGrid         │
│ SmsChannelHandler               │ SMS              │ Twilio / AWS SNS           │
│ PushNotificationChannelHandler  │ PUSH_NOTIFICATION│ Firebase FCM / APNs        │
└─────────────────────────────────┴──────────────────┴────────────────────────────┘

Adding a new channel (e.g. WhatsApp):
  1. Create WhatsAppChannelHandler implements NotificationChannelHandler  (@Component)
  2. Add WHATSAPP to NotificationChannel enum
  3. No other changes needed — ChannelResolverService auto-discovers it
```

#### 2.2.2 Channel Resolver (Factory / Registry Pattern)

```
ChannelResolverService
│
│  Constructor receives List<NotificationChannelHandler> (Spring injects all @Component impls)
│  Builds:  Map<NotificationChannel, NotificationChannelHandler>
│
├── resolve(NotificationChannel) → NotificationChannelHandler
│     Looks up the map; throws NotificationException if channel has no handler
│
└── isChannelSupported(NotificationChannel) → boolean

Design decision:
  Using constructor injection of List<NotificationChannelHandler> means any new
  @Component handler is auto-registered at startup with zero configuration changes.
  The map is built once and is immutable at runtime — no synchronization needed.
```

#### 2.2.3 Processing Orchestrator

```
NotificationProcessingService
│
├── processNotification(Notification)  [@Transactional]
│     1. status → PROCESSING, save
│     2. rateLimitService.isAllowed(channel, userId)
│           └─ false → status = RATE_LIMITED, save, retryService.scheduleRetry()
│     3. handler = channelResolverService.resolve(channel)
│     4. handler.send(notification)
│     5. status → SENT, sentAt = now()
│     6. if recurrenceType != NONE → setNextRecurrenceAt(calculateNextRecurrence())
│     7. save, evictNotificationCache(id)
│     on Exception:
│           status → FAILED, failureReason = e.getMessage()
│           save, evictNotificationCache(id), retryService.scheduleRetry()
│
└── processNotificationAsync(Notification) [@Async("notificationTaskExecutor")]
      Wraps processNotification in a CompletableFuture<Void>
      Thread pool configured in AsyncConfig (core=10, max=50, queue=10000)
```

#### 2.2.4 Rate Limiting

```
RateLimitService  (Redis Sorted Set — Sliding Window Algorithm)
│
│  Redis key:  "ratelimit:{CHANNEL}:{userId}"
│  Window:     60 000 ms (1 minute)
│
├── isAllowed(NotificationChannel, Long userId) → boolean
│     1. ZREMRANGEBYSCORE key 0 (now - 60000)   — expire old entries
│     2. ZCARD key                               — count current window
│     3. if count >= maxAllowed → return false
│     4. ZADD key now UUID                       — record this attempt
│     5. EXPIRE key 60s                          — auto-cleanup
│     → return true
│
└── getMaxPerMinute(NotificationChannel) → int
      EMAIL            = 50  (notification.ratelimit.email.max-per-minute)
      SMS              = 20  (notification.ratelimit.sms.max-per-minute)
      PUSH_NOTIFICATION= 100 (notification.ratelimit.push.max-per-minute)

Redis key namespace:  ratelimit:*
```

---

### 2.3 Scheduling Layer

**Responsibility:** Poll the database on a fixed schedule to dispatch notifications that are due. Completely independent of the HTTP request cycle.

```
NotificationSchedulerService  [@Scheduled]
│
├── processScheduledNotifications()  [every 30 seconds]
│     Guard: distributedLockService.tryLock("scheduler:scheduled", 25s)
│     Query: findScheduledNotificationsReady(SCHEDULED, now)
│     Action: processingService.processNotification(n) for each
│     Finally: distributedLockService.releaseLock("scheduler:scheduled")
│
├── processRecurringNotifications()  [every 60 seconds]
│     Guard: distributedLockService.tryLock("scheduler:recurring", 55s)
│     Query: findRecurringNotificationsDue(now)
│     Action: clone original → new Notification with PENDING status → save → publish event
│             update original.nextRecurrenceAt
│     Finally: distributedLockService.releaseLock("scheduler:recurring")
│
└── processRetryNotifications()  [every 30 seconds]
      Delegates to: retryService.processDueRetries()
```

```
DistributedLockService  (Redis SET NX + Lua safe-release)
│
│  Prevents multiple app instances from running the same scheduler job simultaneously.
│  Each instance has a unique instanceId (UUID at startup).
│
├── tryLock(lockName, ttlSeconds) → boolean
│     Redis SET NX EX ttlSeconds  with value = instanceId
│     → true  = lock acquired
│     → false = another instance holds it
│
└── releaseLock(lockName)
      Lua script: DEL key only if key's value == this instanceId
      (prevents releasing another instance's lock on race conditions)

Redis key namespace:  lock:*
```

---

### 2.4 Retry Layer

**Responsibility:** On delivery failure, schedule a future retry attempt using exponential backoff. Does not block any thread.

```
RetryService
│
├── scheduleRetry(Notification)  [@Transactional]
│     if retryCount >= maxRetries → status = FAILED (permanent), return
│     delay = initialDelayMs × multiplier^retryCount   (exponential backoff)
│     notification.nextRetryAt = now + delay
│     notification.status = RETRY
│     save
│     (scheduler picks it up when nextRetryAt <= now)
│
├── processDueRetries()  [@Transactional]
│     Query: findDueRetryNotifications(now)
│             WHERE status='RETRY' AND retryCount < maxRetries AND nextRetryAt <= now
│     forEach → executeRetry(notification)
│
└── executeRetry(Notification)  [@Transactional]
      retryCount++
      handler = channelResolverService.resolve(channel)
      handler.send(notification)
        → success: status = SENT, sentAt = now()
        → failure: scheduleRetry(notification)  (recurse until maxRetries)

Configuration:
  notification.retry.max-attempts     = 3
  notification.retry.initial-delay-ms = 1000
  notification.retry.multiplier       = 2.0

Backoff schedule (defaults):
  Attempt 1:  1 000 ms  (1 second)
  Attempt 2:  2 000 ms  (2 seconds)
  Attempt 3:  4 000 ms  (4 seconds)
  → FAILED permanently
```

---

### 2.5 Kafka Messaging Layer

**Responsibility:** Decouple the HTTP request from the actual delivery. The API returns immediately after persisting; Kafka drives async processing.

```
NotificationKafkaProducer
├── sendNotificationEvent(NotificationEvent)
│     Topic: notification-send
│     Key:   notificationId (ensures same notification always hits same partition)
│
└── sendRetryEvent(NotificationEvent)
      Topic: notification-retry

NotificationKafkaConsumer
├── consumeSendEvent(NotificationEvent)  [@KafkaListener topic=notification-send]
│     1. deduplicationService.isDuplicate("send:{id}") → skip if true (at-least-once guard)
│     2. notificationRepository.findByIdWithUser(id)
│     3. processingService.processNotification(notification)
│
├── consumeRetryEvent(NotificationEvent) [@KafkaListener topic=notification-retry]
│     1. deduplicationService.isDuplicate("retry:{id}") → skip if true
│     2. notificationRepository.findByIdWithUser(id)
│     3. retryService.executeRetry(notification)
│
├── consumeSendDlt(NotificationEvent)    [@KafkaListener topic=notification-send.DLT]
│     Logs: manual intervention required
│
└── consumeRetryDlt(NotificationEvent)   [@KafkaListener topic=notification-retry.DLT]
      Logs: manual intervention required

NotificationEvent  (Serializable — Kafka message payload)
├── notificationId : Long
├── eventType      : String  ("SEND" | "RETRY")
└── timestamp      : LocalDateTime
```

---

### 2.6 Data Model

```
User  (@Entity)
├── id            : Long  (PK)
├── username      : String  (unique)
├── email         : String
├── phoneNumber   : String
├── deviceToken   : String
├── active        : boolean  (default true)
└── channelPreferences : List<UserChannelPreference>  (OneToMany, CascadeAll)

UserChannelPreference  (@Entity)
├── id      : Long  (PK)
├── user    : User  (ManyToOne)
├── channel : NotificationChannel  (enum)
└── enabled : boolean

Notification  (@Entity)
├── id               : Long  (PK)
├── user             : User  (ManyToOne, LAZY)
├── title            : String
├── message          : String  (length 2000)
├── channel          : NotificationChannel  (enum)
├── priority         : NotificationPriority  (enum)
├── status           : NotificationStatus  (enum)
├── recurrenceType   : RecurrenceType  (enum)
├── scheduledAt      : LocalDateTime
├── sentAt           : LocalDateTime
├── nextRecurrenceAt : LocalDateTime
├── nextRetryAt      : LocalDateTime
├── retryCount       : int
├── maxRetries       : int
└── failureReason    : String

DB Indexes (for scheduler query performance):
  idx_notification_status        → status
  idx_notification_priority      → priority
  idx_notification_scheduled_at  → scheduledAt
  idx_notification_next_retry_at → nextRetryAt

Enums:
  NotificationChannel  : EMAIL | SMS | PUSH_NOTIFICATION
  NotificationPriority : HIGH | MEDIUM | LOW
  NotificationStatus   : PENDING | SCHEDULED | PROCESSING | SENT | FAILED | RETRY
                         RATE_LIMITED
  RecurrenceType       : NONE | DAILY | WEEKLY | MONTHLY
```

---

### 2.7 Redis Key Namespace Summary

```
Prefix          Pattern                                      Layer             TTL
──────────────────────────────────────────────────────────────────────────────────
notif:          notif:users::{id}                            Cache             10 min
                notif:notifications::{id}                    Cache              2 min
                notif:user-notifications::{userId}:{p}:{s}  Cache             60 sec
dedup:          dedup:content:{sha256}                       API dedup         60 sec
                dedup:send:{notificationId}                  Kafka dedup      300 sec
                dedup:retry:{notificationId}                 Kafka dedup      300 sec
ratelimit:      ratelimit:{CHANNEL}:{userId}                 Rate limiting     60 sec
lock:           lock:scheduler:scheduled                     Distributed lock  25 sec
                lock:scheduler:recurring                     Distributed lock  55 sec
```

---

## 3. Separation of Concerns — Responsibility Matrix

```
Concern                       │ Class(es) responsible
──────────────────────────────┼──────────────────────────────────────────────────────
HTTP input / validation       │ NotificationController, UserController
                              │ SendNotificationRequest, UserRegistrationRequest (JSR-303)
──────────────────────────────┼──────────────────────────────────────────────────────
Notification generation       │ NotificationService
  + duplicate rejection       │   └── DeduplicationService (content hash, Redis SET NX)
──────────────────────────────┼──────────────────────────────────────────────────────
Async event routing           │ NotificationKafkaProducer → (Kafka) → NotificationKafkaConsumer
──────────────────────────────┼──────────────────────────────────────────────────────
Channel delivery              │ NotificationProcessingService  (orchestrator)
  - channel selection         │   └── ChannelResolverService  (Strategy registry)
  - actual send               │         ├── EmailChannelHandler
                              │         ├── SmsChannelHandler
                              │         └── PushNotificationChannelHandler
  - rate limiting             │   └── RateLimitService  (Redis sliding window)
──────────────────────────────┼──────────────────────────────────────────────────────
Scheduling                    │ NotificationSchedulerService  (@Scheduled polling)
  + distributed safety        │   └── DistributedLockService  (Redis SET NX + Lua)
──────────────────────────────┼──────────────────────────────────────────────────────
Retry / backoff               │ RetryService  (exponential backoff, RETRY status)
──────────────────────────────┼──────────────────────────────────────────────────────
Persistence                   │ NotificationRepository, UserRepository  (Spring Data JPA)
──────────────────────────────┼──────────────────────────────────────────────────────
Caching                       │ RedisConfig (RedisCacheManager)
                              │ @Cacheable / @CachePut / @CacheEvict on UserService,
                              │ NotificationService, NotificationProcessingService
──────────────────────────────┼──────────────────────────────────────────────────────
Error handling                │ GlobalExceptionHandler (@RestControllerAdvice)
                              │   UserNotFoundException    → 404
                              │   NotificationException    → 400
                              │   DuplicateNotificationException → 409
                              │   MethodArgumentNotValidException → 400
                              │   Exception (catch-all)    → 500
```

---

## 4. Channel Extensibility — How to Add a New Channel

The `NotificationChannelHandler` interface is the single extension point for delivery channels.
The system uses **zero-configuration auto-discovery** via Spring's component scan.

```
Step 1 — Add enum value
───────────────────────
  // NotificationChannel.java
  public enum NotificationChannel {
      EMAIL, SMS, PUSH_NOTIFICATION,
      WHATSAPP   // ← add here
  }

Step 2 — Implement the interface
─────────────────────────────────
  @Slf4j
  @Component                                           // ← Spring registers it
  public class WhatsAppChannelHandler
          implements NotificationChannelHandler {      // ← single interface

      @Override
      public void send(Notification notification) {
          // call WhatsApp Business API here
          // throw RuntimeException on failure → RetryService handles backoff
      }

      @Override
      public NotificationChannel getChannel() {
          return NotificationChannel.WHATSAPP;         // ← links to the enum
      }

      @Override
      public boolean supports(NotificationChannel channel) {
          return NotificationChannel.WHATSAPP.equals(channel);
      }
  }

Step 3 — Nothing else
──────────────────────
  ChannelResolverService receives List<NotificationChannelHandler> in its constructor.
  Spring auto-injects all @Component implementations — WhatsAppChannelHandler is
  included automatically. The handler map entry is created at startup.

  RateLimitService.getMaxPerMinute() will return a sensible default (push limit)
  until you add:
    notification.ratelimit.whatsapp.max-per-minute=30
```

**Interface contract rules for channel implementors:**
- `send()` must be synchronous — `NotificationProcessingService` wraps it in `@Async`
- Throw any `RuntimeException` on failure — do not swallow exceptions
- Do not change notification status inside `send()` — that is `NotificationProcessingService`'s job
- Do not retry inside `send()` — `RetryService` owns backoff logic

---

## 5. Key Design Patterns Used

| Pattern | Where | Why |
|---|---|---|
| **Strategy** | `NotificationChannelHandler` interface + implementations | Swap/add channels without touching orchestration logic |
| **Factory / Registry** | `ChannelResolverService` | Single lookup point; decouples caller from concrete handlers |
| **Template Method** | `NotificationProcessingService.processNotification()` | Fixed algorithm (rate-check → dispatch → status-update) with pluggable `send()` step |
| **Observer / Event-Driven** | Kafka topics + `NotificationEvent` | Decouples HTTP request from delivery; enables backpressure and replay |
| **Repository** | `NotificationRepository`, `UserRepository` | Isolates persistence; enables query optimisation without touching business logic |
| **Builder** | All DTOs, entities, events | Readable, immutable-friendly object construction |
| **Null Object / Default** | Priority defaults to `MEDIUM`, RecurrenceType defaults to `NONE` | Prevents NPEs without forcing callers to supply every field |

---

## 6. Request Flow Diagrams

### 6.1 Immediate Notification (Happy Path)

```
Client
  │  POST /api/notifications/send
  ▼
NotificationController
  │  calls sendNotification(request)
  ▼
NotificationService
  │  1. Content dedup check (Redis SET NX)  ──── duplicate? ──► 409 Conflict
  │  2. findUserById (Redis cache hit / DB)
  │  3. Build Notification (status=PENDING)
  │  4. notificationRepository.save()
  │  5. kafkaProducer.sendNotificationEvent()
  │  6. return NotificationResponse (200 OK)
  ▼
[Kafka topic: notification-send]
  ▼
NotificationKafkaConsumer.consumeSendEvent()
  │  1. Kafka-level dedup check (Redis SET NX)
  │  2. Load notification + user from DB
  │  3. processingService.processNotification()
  ▼
NotificationProcessingService
  │  1. status → PROCESSING
  │  2. RateLimitService.isAllowed()  ─── rate limited? ──► status=RATE_LIMITED → RetryService
  │  3. ChannelResolverService.resolve(EMAIL)
  │  4. EmailChannelHandler.send()  ─── failure? ──► status=FAILED → RetryService
  │  5. status → SENT, sentAt = now()
  │  6. save, evict cache
  ▼
  Done
```

### 6.2 Failed Delivery → Retry Flow

```
EmailChannelHandler.send()  throws RuntimeException
  ▼
NotificationProcessingService  catch block
  │  status = FAILED, failureReason = message
  │  save
  ▼
RetryService.scheduleRetry()
  │  retryCount < maxRetries?
  │    yes → status = RETRY, nextRetryAt = now + backoff(retryCount), save
  │    no  → status = FAILED (permanent), save
  ▼
  ... (30 seconds later) ...
NotificationSchedulerService.processRetryNotifications()
  │  findDueRetryNotifications(now)
  ▼
RetryService.executeRetry()
  │  retryCount++
  │  ChannelResolverService.resolve(channel)
  │  handler.send()
  │    success → status = SENT
  │    failure → scheduleRetry() again (until maxRetries)
```

### 6.3 Scheduled Notification Flow

```
Client
  │  POST /api/notifications/send  { scheduledAt: "2026-04-01T09:00:00" }
  ▼
NotificationService
  │  scheduledAt is in future → status = SCHEDULED, save (no Kafka publish)
  │  return 200 OK
  ▼
  ... (scheduler polls every 30 seconds) ...
NotificationSchedulerService.processScheduledNotifications()
  │  DistributedLockService.tryLock("scheduler:scheduled")
  │  findScheduledNotificationsReady(SCHEDULED, now)
  ▼
NotificationProcessingService.processNotification()  (same path as immediate)
```
