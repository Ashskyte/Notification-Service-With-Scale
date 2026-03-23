# Notification System

A scalable, extensible Notification System built with **Java 17** and **Spring Boot 3.2**. Supports multi-channel delivery (Email, SMS, Push), scheduling, prioritization, batching, retry with exponential backoff, **Redis-backed caching**, and **content-based deduplication**.

## Architecture

```
├── controller/          # REST API endpoints
├── service/             # Business logic (notification, user, scheduler, retry, deduplication, rate limiting)
├── channel/             # Strategy pattern: pluggable channel handlers
├── config/              # Redis, Kafka, Async configuration
├── model/               # JPA entities and enums
├── dto/                 # Request/Response DTOs
├── repository/          # Spring Data JPA repositories
└── exception/           # Global exception handling
```

### Design Patterns
- **Strategy Pattern** — Channel handlers implement `NotificationChannelHandler` interface, enabling easy addition of new channels (WhatsApp, Slack, etc.)
- **Factory/Resolver** — `ChannelResolverService` auto-discovers and maps all registered channel handlers
- **Builder Pattern** — Lombok `@Builder` for clean object construction

## Features

| Feature | Description |
|---|---|
| **Multi-Channel** | Email, SMS, Push Notifications |
| **Scheduling** | Immediate or future delivery |
| **Recurring** | Daily, Weekly, Monthly recurrence |
| **Priority** | HIGH, MEDIUM, LOW with priority-based processing |
| **Batching** | Bulk notification API with configurable batch size |
| **Retry** | Exponential backoff (configurable attempts, delay, multiplier) |
| **Caching** | Redis-backed caching for users and notifications with per-cache TTLs |
| **Deduplication** | Two-layer: content-based (API) + Kafka event dedup via Redis SET NX |
| **Rate Limiting** | Per-user, per-channel distributed rate limiting via Redis |
| **Extensibility** | Add new channels by implementing one interface |

## Tech Stack

- Java 17
- Spring Boot 3.2.4
- Spring Data JPA + H2 In-Memory Database
- Apache Kafka 3.7.0
- **Redis 7 (Alpine)** — Caching, Deduplication, Rate Limiting
- Spring Boot Actuator
- Docker / Podman (Containerization)
- Lombok
- JUnit 5 + Mockito + AssertJ


## Getting Started

### Prerequisites
- Java 17+
- Maven 3.8+
- **Docker Desktop** (or **Podman**) — for containerized setup

---

### Option 1: Docker Compose (Recommended — One Command)

This starts **Kafka + Kafka UI + Redis + Notification Service** together with a single command.

```bash
# Build and start everything
docker-compose up --build

# Or in detached (background) mode
docker-compose up --build -d
```

| Service | URL |
|---|---|
| Notification Service | `http://localhost:8081` |
| Kafka UI | `http://localhost:8090` |
| Redis | `localhost:6379` |
| Health Check | `http://localhost:8081/actuator/health` |

**Useful commands:**
```bash
# View logs
docker-compose logs -f notification-service

# Check container status
docker-compose ps

# Inspect Redis keys (connect to Redis CLI inside container)
docker exec -it redis redis-cli keys "notif:*"
docker exec -it redis redis-cli keys "dedup:*"

# Stop everything
docker-compose down

# Stop and remove volumes
docker-compose down -v
```

> **Note:** With Podman, use `podman-compose` in place of `docker-compose`.

---

### Option 2: Manual Setup with Podman + Maven

If you prefer running the Spring Boot app locally (outside a container) with only Kafka and Redis in Podman:

**Step 1 — Start Kafka, Kafka UI & Redis (Podman)**
```powershell
# Create a pod with port mappings
podman pod create --name kafka-pod -p 9092:9092 -p 8090:8080 -p 6379:6379

# Start Kafka broker inside the pod
podman run -d --pod kafka-pod --name kafka apache/kafka:3.7.0

# Start Kafka UI inside the pod (Windows PowerShell — single line)
podman run -d --pod kafka-pod --name kafka-ui -e DYNAMIC_CONFIG_ENABLED=true -e KAFKA_CLUSTERS_0_NAME=local -e KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS=localhost:9092 provectuslabs/kafka-ui:latest

# Start Redis inside the pod
podman run -d --pod kafka-pod --name redis redis:7-alpine redis-server --maxmemory 256mb --maxmemory-policy allkeys-lru
```

**Step 2 — Run the Application**
```bash
mvn clean install
mvn spring-boot:run
```

| Service | URL |
|---|---|
| Notification Service | `http://localhost:8080` |
| Kafka UI | `http://localhost:8090` |
| Redis | `localhost:6379` |

**Step 3 — Cleanup**
```powershell
# Stop the entire pod (Kafka + Kafka UI + Redis)
podman pod stop kafka-pod

# Remove the pod and all its containers
podman pod rm kafka-pod
```

---

### H2 Console
Access at `http://localhost:8081/h2-console` (or `:8080` if running via Maven) with:
- JDBC URL: `jdbc:h2:mem:notificationdb`
- Username: `sa`
- Password: *(empty)*

## API Endpoints

### User APIs
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/users` | Register a new user |
| GET | `/api/users/{id}` | Get user by ID |
| GET | `/api/users` | Get all users |
| PUT | `/api/users/{id}/preferences` | Update channel preferences |

### Notification APIs
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/notifications/send` | Send or schedule a notification |
| POST | `/api/notifications/send/bulk` | Send bulk notifications |
| GET | `/api/notifications/{id}` | Get notification by ID |
| GET | `/api/notifications/user/{userId}` | Get user notifications (paginated) |
| GET | `/api/notifications/user/{userId}/status/{status}` | Get by user and status |

### Example: Register User
```json
POST /api/users
{
  "username": "john_doe",
  "email": "john@example.com",
  "phoneNumber": "+1234567890",
  "deviceToken": "fcm-token-abc",
  "preferredChannels": ["EMAIL", "SMS"]
}
```

### Example: Send Notification
```json
POST /api/notifications/send
{
  "userId": 1,
  "title": "Welcome!",
  "message": "Welcome to our platform",
  "channel": "EMAIL",
  "priority": "HIGH"
}
```

> Sending the **exact same payload again within 60 seconds** returns `HTTP 409 Conflict` — duplicate rejected.

### Example: Schedule Notification
```json
POST /api/notifications/send
{
  "userId": 1,
  "title": "Reminder",
  "message": "Your appointment is tomorrow",
  "channel": "SMS",
  "priority": "MEDIUM",
  "scheduledAt": "2025-04-01T09:00:00",
  "recurrenceType": "WEEKLY"
}
```

### Example: Bulk Notification
```json
POST /api/notifications/send/bulk
{
  "userIds": [1, 2, 3],
  "title": "Campaign",
  "message": "Check out our new feature!",
  "channel": "PUSH_NOTIFICATION",
  "priority": "LOW"
}
```

## Database Schema

### users
| Column | Type | Description |
|--------|------|-------------|
| id | BIGINT (PK) | Auto-generated ID |
| username | VARCHAR | Unique username |
| email | VARCHAR | Email address |
| phone_number | VARCHAR | Phone number |
| device_token | VARCHAR | Push notification token |
| active | BOOLEAN | Account status |
| created_at | TIMESTAMP | Creation time |

### notifications
| Column | Type | Description |
|--------|------|-------------|
| id | BIGINT (PK) | Auto-generated ID |
| user_id | BIGINT (FK) | Reference to users |
| title | VARCHAR | Notification title |
| message | VARCHAR(2000) | Notification body |
| channel | ENUM | EMAIL, SMS, PUSH_NOTIFICATION |
| priority | ENUM | HIGH, MEDIUM, LOW |
| status | ENUM | PENDING, SCHEDULED, PROCESSING, SENT, FAILED, RETRY |
| recurrence_type | ENUM | NONE, DAILY, WEEKLY, MONTHLY |
| scheduled_at | TIMESTAMP | Scheduled delivery time |
| sent_at | TIMESTAMP | Actual delivery time |
| retry_count | INT | Current retry attempt |
| max_retries | INT | Maximum retry attempts |
| failure_reason | VARCHAR | Last failure message |

### user_channel_preferences
| Column | Type | Description |
|--------|------|-------------|
| id | BIGINT (PK) | Auto-generated ID |
| user_id | BIGINT (FK) | Reference to users |
| channel | ENUM | Preferred channel |
| enabled | BOOLEAN | Whether preference is active |

## Redis Integration

Redis serves three distinct roles in this system:

### 1. Distributed Caching (`spring-cache` + `RedisCacheManager`)
Reduces database load by caching frequently accessed data with automatic TTL-based expiry.

| Cache Name | TTL | Cached Data |
|---|---|---|
| `users` | 10 minutes | Individual user lookups by ID |
| `notifications` | 2 minutes | Individual notification lookups by ID |
| `user-notifications` | 60 seconds | Paginated notification lists per user |

- Serialization: `GenericJackson2JsonRedisSerializer` with Java 8 time support
- Key prefix: `notif:` (all keys namespaced to avoid collisions)
- Memory policy: `allkeys-lru` with 256MB cap (configured in Docker)

### 2. Content-Based Deduplication (Two Layers)

| Layer | Key Pattern | TTL | Purpose |
|---|---|---|---|
| **API-level** | `dedup:content:{SHA-256(userId+channel+title+message)}` | 60s (configurable) | Rejects identical API requests within the window → `HTTP 409 Conflict` |
| **Kafka-level** | `dedup:send:{notificationId}` / `dedup:retry:{notificationId}` | 300s (configurable) | Skips Kafka at-least-once redeliveries |

Uses Redis `SET NX` (set-if-not-exists) — atomic and TTL-controlled, no manual cleanup needed.

**Example — sending the same notification twice:**
```
POST /api/notifications/send  →  HTTP 200 OK       (first request)
POST /api/notifications/send  →  HTTP 409 Conflict (duplicate within 60s window)
```

### 3. Rate Limiting
Distributed per-user, per-channel rate limiting using Redis sliding counters — prevents a single user from flooding a channel and respects downstream provider limits.

### Configuration

```properties
# Redis connection
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.lettuce.pool.max-active=16
spring.data.redis.lettuce.pool.max-idle=8

# Deduplication TTLs
notification.dedup.ttl-seconds=300          # Kafka event dedup window
notification.dedup.content-ttl-seconds=60  # API content dedup window
```

## Running Tests
```bash
mvn test
```

## Adding a New Channel

1. Create a new class implementing `NotificationChannelHandler`:
```java
@Component
public class WhatsAppChannelHandler implements NotificationChannelHandler {
    @Override
    public void send(Notification notification) { /* implementation */ }

    @Override
    public NotificationChannel getChannel() { return NotificationChannel.WHATSAPP; }

    @Override
    public boolean supports(NotificationChannel channel) {
        return NotificationChannel.WHATSAPP.equals(channel);
    }
}
```
2. Add `WHATSAPP` to the `NotificationChannel` enum.
3. The `ChannelResolverService` auto-discovers the new handler — no other changes needed.

---
## Next Steps - Making the Service Production-Ready and Scalable
The current system handles ~50-100 msg/sec with a single JVM, H2 in-memory DB, and 1 Kafka broker. Below are the incremental steps to scale it to 10,000+ msg/sec for 1M+ users.
### 1. Replace H2 with PostgreSQL (Persistent, Production-Grade DB)
- Swap H2 in-memory database with **PostgreSQL** (or MySQL).
- Use **connection pooling** (HikariCP - already included in Spring Boot).
- Add database **indexes** on `user_id`, `status`, `scheduled_at`, and `channel` columns for fast queries.
- Set out **read replicas** to offload read-heavy queries (e.g., notification history lookups).
- Use **Flyway** or **Liquibase** for database schema migration management.
### 2. Scale Kafka for High Throughput
- Move from **1 Kafka broker to 3-5 broker cluster** for fault tolerance and throughput.
- Increase partitions from **3 to 30-50 per topic** to allow more parallel consumers.
- Use **partition keys** (e.g., `userId`) to ensure ordered delivery per user.
- Enable **idempotent producers** (`enable.idempotence=true`) to prevent duplicate messages.
- Configure proper **replication factor** (min 3) for durability.
### 3. Horizontal Scaling - Multiple Application Instances
- Deploy **5-10 Spring Boot instances** behind a **load balancer** (NGINX / AWS ALB).
- Each instance runs **5 Kafka consumer threads** = 50 total consumers across the cluster.
- Use **Kafka consumer groups** so partitions are auto-distributed across instances.
- Make the application fully **stateless** (no in-memory state) so any instance can handle any request.
### 4. ✅ Redis for Caching and Deduplication — **Implemented**
- ✅ **User and notification caching** via `RedisCacheManager` with per-cache TTLs (users: 10min, notifications: 2min)
- ✅ **Two-layer deduplication** — content-based (API, 60s TTL) + Kafka event dedup (300s TTL) using Redis `SET NX`
- ✅ **Distributed rate limiting** per user/channel via Redis sliding counters
- ✅ Redis 7 (Alpine) containerized in Docker Compose with `allkeys-lru` eviction and 256MB memory cap
### 5. Rate Limiting per Channel
- Email providers (SES, SendGrid) and SMS gateways (Twilio) enforce **API rate limits**.
- Extend the existing Redis rate limiter with **per-provider limits** and a token bucket algorithm.
- Add a **back-pressure mechanism** — if rate limit is hit, slow down Kafka consumer consumption.
### 6. Replace Simulated Channels with Real Providers
| Channel | Current | Production |
|---------|---------|------------|
| Email | Simulated (log only) | **AWS SES** / **SendGrid** with connection pooling |
| SMS | Simulated (log only) | **Twilio** / **AWS SNS** |
| Push | Simulated (log only) | **Firebase Cloud Messaging (FCM)** / **APNs** |
- Use **async HTTP clients** (WebClient / RestClient) with retry and circuit breaker for external API calls.
### 7. Add Circuit Breaker and Bulkhead Patterns
- Use **Resilience4j** Circuit Breaker to prevent cascading failures when an external provider goes down.
- Apply **Bulkhead** pattern to isolate failures per channel (e.g., SMS failure doesn't block Email).
- Configure fallback strategies (e.g., queue for retry, switch to secondary provider).
### 8. Observability and Monitoring
- Add **distributed tracing** with Micrometer Tracing + Zipkin/Jaeger (trace a notification across Kafka to consumer to provider).
- Export **metrics** to Prometheus and visualize with **Grafana** dashboards.
  - Track: throughput (msg/sec), latency (p50/p95/p99), failure rate per channel, Kafka consumer lag.
- Centralize **logs** with ELK Stack (Elasticsearch + Logstash + Kibana) or Loki.
- Set up **alerts** for consumer lag spikes, high failure rates, and provider outages.
### 9. Dead Letter Queue (DLQ) and Failed Notification Handling
- Configure a **Kafka Dead Letter Topic** for messages that fail after all retries.
- Build an **admin dashboard / API** to inspect, replay, or manually resolve failed notifications.
- Store failure metadata (reason, timestamp, retry count) for debugging.
### 10. Security Hardening
- Add **Spring Security** with JWT-based authentication for all API endpoints.
- Encrypt sensitive fields (email, phone number) **at rest** in the database.
- Use **TLS/SSL** for Kafka broker communication and external API calls.
- Implement **API rate limiting** on REST endpoints to prevent abuse.
### 11. Containerization and Orchestration
- Current: Single `docker-compose` setup (Kafka + Redis + Notification Service).
- Production: Deploy on **Kubernetes (K8s)** with:
  - **Horizontal Pod Autoscaler (HPA)** - auto-scale based on CPU/Kafka lag.
  - **Liveness and Readiness probes** via Spring Actuator health endpoints.
  - **Helm charts** for repeatable deployments across environments.
  - **ConfigMaps and Secrets** for externalized configuration.
### 12. Database Optimization for Scale
- Implement **table partitioning** on the `notifications` table by `created_at` (monthly partitions).
- Archive old notifications (>90 days) to cold storage (S3 / cheaper DB).
- Use **batch inserts** for bulk notification persistence.
- Consider **CQRS** - separate write model (PostgreSQL) from read model (Elasticsearch) for fast search/filtering.
---
### Summary: Current vs Scaled Architecture
| Component | Current | Scaled (10K+ msg/sec) |
|---|---|---|
| Kafka | 1 broker, 3 partitions | 3-5 brokers, 30-50 partitions/topic |
| Consumers | 3 threads (1 JVM) | 10 JVMs x 5 threads = 50 consumers |
| Database | H2 in-memory | PostgreSQL cluster (write + read replicas) |
| Caching | ✅ Redis (`users` 10min, `notifications` 2min, `user-notifications` 60s) | Redis Cluster (HA, multi-shard) |
| Deduplication | ✅ Redis SET NX (content 60s + Kafka event 300s) | Same, scaled with Redis Cluster |
| Rate Limiting | ✅ Redis per-user/channel sliding counter | Per-provider limits + back-pressure |
| App Instances | 1 Spring Boot | 5-10 instances behind load balancer |
| Email/SMS | Simulated | SES / SendGrid / Twilio with connection pool |
| Monitoring | Actuator only | Prometheus + Grafana + Zipkin + ELK |
| Deployment | Docker Compose (Kafka + Redis + App) | Kubernetes with HPA + Helm |
