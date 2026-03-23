package com.notification.service;
import com.notification.dto.BulkNotificationRequest;
import com.notification.dto.NotificationResponse;
import com.notification.dto.SendNotificationRequest;
import com.notification.event.NotificationEvent;
import com.notification.exception.DuplicateNotificationException;
import com.notification.exception.DuplicateNotificationException;
import com.notification.exception.NotificationException;
import com.notification.kafka.NotificationKafkaProducer;
import com.notification.model.Notification;
import com.notification.model.User;
import com.notification.model.enums.NotificationPriority;
import com.notification.model.enums.NotificationStatus;
import com.notification.model.enums.RecurrenceType;
import com.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationRepository notificationRepository;
    private final UserService userService;
    private final NotificationKafkaProducer kafkaProducer;
    private final DeduplicationService deduplicationService;

    @Value("${notification.batch.size:100}")
    private int batchSize;

    @Transactional
    public NotificationResponse sendNotification(SendNotificationRequest request) {
        // Content-based deduplication: reject identical notifications for the same user within the dedup window
        String contentKey = buildContentDeduplicationKey(request);
        if (deduplicationService.isContentDuplicate(contentKey)) {
            throw new DuplicateNotificationException(
                    "Duplicate notification: identical content already submitted for user ["
                    + request.getUserId() + "] within the deduplication window");
        }

        User user = userService.findUserById(request.getUserId());
        NotificationPriority priority = request.getPriority() != null
                ? request.getPriority()
                : NotificationPriority.MEDIUM;
        RecurrenceType recurrenceType = request.getRecurrenceType() != null
                ? request.getRecurrenceType()
                : RecurrenceType.NONE;
        Notification notification = Notification.builder()
                .user(user)
                .title(request.getTitle())
                .message(request.getMessage())
                .channel(request.getChannel())
                .priority(priority)
                .recurrenceType(recurrenceType)
                .status(NotificationStatus.PENDING)
                .build();
        if (request.getScheduledAt() != null) {
            if (request.getScheduledAt().isBefore(LocalDateTime.now())) {
                throw new NotificationException("Scheduled time must be in the future");
            }
            notification.setScheduledAt(request.getScheduledAt());
            notification.setStatus(NotificationStatus.SCHEDULED);
            notification = notificationRepository.save(notification);
            log.info("Notification [{}] scheduled for {}", notification.getId(), request.getScheduledAt());
            return NotificationResponse.fromEntity(notification);
        }
        notification = notificationRepository.save(notification);
        kafkaProducer.sendNotificationEvent(NotificationEvent.ofSend(notification.getId()));
        return NotificationResponse.fromEntity(notification);
    }
    @Transactional
    public List<NotificationResponse> sendBulkNotifications(BulkNotificationRequest request) {
        List<NotificationResponse> responses = new ArrayList<>();
        NotificationPriority priority = request.getPriority() != null
                ? request.getPriority()
                : NotificationPriority.MEDIUM;
        List<List<Long>> batches = partitionList(request.getUserIds(), batchSize);
        for (List<Long> batch : batches) {
            for (Long userId : batch) {
                try {
                    User user = userService.findUserById(userId);
                    Notification notification = Notification.builder()
                            .user(user)
                            .title(request.getTitle())
                            .message(request.getMessage())
                            .channel(request.getChannel())
                            .priority(priority)
                            .recurrenceType(RecurrenceType.NONE)
                            .status(NotificationStatus.PENDING)
                            .build();
                    notification = notificationRepository.save(notification);
                    kafkaProducer.sendNotificationEvent(NotificationEvent.ofSend(notification.getId()));
                    responses.add(NotificationResponse.fromEntity(notification));
                } catch (Exception e) {
                    log.error("Failed to send bulk notification to user [{}]: {}", userId, e.getMessage());
                }
            }
        }
        log.info("Bulk notification completed: {}/{} sent successfully",
                responses.stream().filter(r -> r.getStatus() == NotificationStatus.SENT).count(),
                request.getUserIds().size());
        return responses;
    }
    @Transactional(readOnly = true)
    @Cacheable(value = "notifications", key = "#id")
    public NotificationResponse getNotificationById(Long id) {
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new NotificationException("Notification not found with id: " + id));
        return NotificationResponse.fromEntity(notification);
    }
    @Transactional(readOnly = true)
    @Cacheable(value = "user-notifications", key = "#userId + ':' + #page + ':' + #size")
    public Page<NotificationResponse> getNotificationsByUserId(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return notificationRepository.findByUserId(userId, pageable)
                .map(NotificationResponse::fromEntity);
    }
    @Transactional(readOnly = true)
    public List<NotificationResponse> getNotificationsByUserAndStatus(Long userId, NotificationStatus status) {
        return notificationRepository.findByUserIdAndStatus(userId, status).stream()
                .map(NotificationResponse::fromEntity)
                .collect(Collectors.toList());
    }
    private <T> List<List<T>> partitionList(List<T> list, int partitionSize) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += partitionSize) {
            partitions.add(list.subList(i, Math.min(i + partitionSize, list.size())));
        }
        return partitions;
    }

    /**
     * Builds a content-based deduplication key by hashing the combination of
     * userId + title + message + channel. This key is used to detect duplicate
     * API requests within the deduplication TTL window (see notification.dedup.ttl-seconds).
     */
    private String buildContentDeduplicationKey(SendNotificationRequest request) {
        String raw = request.getUserId() + ":"
                + request.getChannel() + ":"
                + request.getTitle() + ":"
                + request.getMessage();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return "content:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in the JDK — fall back to raw key
            log.warn("SHA-256 not available, using raw content key for deduplication");
            return "content:" + raw;
        }
    }
}