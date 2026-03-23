package com.notification.service;
import com.notification.model.Notification;
import com.notification.model.enums.NotificationStatus;
import com.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationSchedulerService {
    private final NotificationRepository notificationRepository;
    private final NotificationProcessingService processingService;
    private final RetryService retryService;
    private final DistributedLockService lockService;
    @Scheduled(fixedRate = 30000) // Every 30 seconds
    @Transactional
    public void processScheduledNotifications() {
        if (!lockService.tryLock("scheduler:scheduled", 25)) {
            log.debug("Another instance holds the scheduler:scheduled lock - skipping");
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            List<Notification> scheduledNotifications = notificationRepository
                    .findScheduledNotificationsReady(NotificationStatus.SCHEDULED, now);
            if (!scheduledNotifications.isEmpty()) {
                log.info("Processing {} scheduled notifications", scheduledNotifications.size());
                for (Notification notification : scheduledNotifications) {
                    processingService.processNotification(notification);
                }
            }
        } finally {
            lockService.releaseLock("scheduler:scheduled");
        }
    }
    @Scheduled(fixedRate = 60000) // Every 60 seconds
    @Transactional
    public void processRecurringNotifications() {
        if (!lockService.tryLock("scheduler:recurring", 55)) {
            log.debug("Another instance holds the scheduler:recurring lock - skipping");
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            List<Notification> recurringNotifications = notificationRepository
                    .findRecurringNotificationsDue(now);
            if (!recurringNotifications.isEmpty()) {
                log.info("Processing {} recurring notifications", recurringNotifications.size());
                for (Notification original : recurringNotifications) {
                    Notification recurring = Notification.builder()
                            .user(original.getUser())
                            .title(original.getTitle())
                            .message(original.getMessage())
                            .channel(original.getChannel())
                            .priority(original.getPriority())
                            .recurrenceType(original.getRecurrenceType())
                            .status(NotificationStatus.PENDING)
                            .build();
                    recurring = notificationRepository.save(recurring);
                    processingService.processNotification(recurring);
                    original.setNextRecurrenceAt(
                            processingService.calculateNextRecurrence(now, original.getRecurrenceType()));
                    notificationRepository.save(original);
                }
            }
        } finally {
            lockService.releaseLock("scheduler:recurring");
        }
    }
    @Scheduled(fixedRate = 10000) // Every 10 seconds - check for due retries
    public void retryFailedNotifications() {
        if (!lockService.tryLock("scheduler:retry", 8)) {
            log.debug("Another instance holds the scheduler:retry lock - skipping");
            return;
        }
        try {
            log.debug("Running retry job for due notifications");
            retryService.processDueRetries();
        } finally {
            lockService.releaseLock("scheduler:retry");
        }
    }
}