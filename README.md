# Notification System

A scalable, extensible Notification System built with **Java 17** and **Spring Boot 3.2**. Supports multi-channel delivery (Email, SMS, Push), scheduling, prioritization, batching, and retry with exponential backoff.

## Architecture

```
├── controller/          # REST API endpoints
├── service/             # Business logic (notification, user, scheduler, retry)
├── channel/             # Strategy pattern: pluggable channel handlers
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
| **Extensibility** | Add new channels by implementing one interface |

## Tech Stack

- Java 17
- Spring Boot 3.2.4
- Spring Data JPA + H2 In-Memory Database
- Apache Kafka 3.7.0
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

This starts **Kafka + Kafka UI + Notification Service** together with a single command.

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
| Health Check | `http://localhost:8081/actuator/health` |

**Useful commands:**
```bash
# View logs
docker-compose logs -f notification-service

# Check container status
docker-compose ps

# Stop everything
docker-compose down

# Stop and remove volumes
docker-compose down -v
```

> **Note:** With Podman, use `podman-compose` in place of `docker-compose`.

---

### Option 2: Manual Setup with Podman + Maven

If you prefer running the Spring Boot app locally (outside a container) with only Kafka in Podman:

**Step 1 — Start Kafka & Kafka UI (Podman)**
```powershell
# Create a pod with port mappings
podman pod create --name kafka-pod -p 9092:9092 -p 8090:8080

# Start Kafka broker inside the pod
podman run -d --pod kafka-pod --name kafka apache/kafka:3.7.0

# Start Kafka UI inside the pod (Windows PowerShell — single line)
podman run -d --pod kafka-pod --name kafka-ui -e DYNAMIC_CONFIG_ENABLED=true -e KAFKA_CLUSTERS_0_NAME=local -e KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS=localhost:9092 provectuslabs/kafka-ui:latest
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

**Step 3 — Cleanup**
```powershell
# Stop the entire pod (Kafka + Kafka UI)
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
