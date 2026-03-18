package com.notification.service;

import com.notification.dto.BulkNotificationRequest;
import com.notification.dto.NotificationResponse;
import com.notification.dto.SendNotificationRequest;
import com.notification.exception.NotificationException;
import com.notification.model.Notification;
import com.notification.model.User;
import com.notification.model.enums.NotificationPriority;
import com.notification.model.enums.NotificationStatus;
import com.notification.model.enums.RecurrenceType;
import com.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserService userService;
    private final NotificationProcessingService processingService;

    @Value("${notification.batch.size:100}")
    private int batchSize;

    @Transactional
    public NotificationResponse sendNotification(SendNotificationRequest request) {
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
        processingService.processNotificationAsync(notification);
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
                    processingService.processNotificationAsync(notification);
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
    public NotificationResponse getNotificationById(Long id) {
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new NotificationException("Notification not found with id: " + id));
        return NotificationResponse.fromEntity(notification);
    }

    @Transactional(readOnly = true)
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
}
