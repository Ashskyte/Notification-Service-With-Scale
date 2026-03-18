package com.notification.service;

import com.notification.channel.NotificationChannelHandler;
import com.notification.model.Notification;
import com.notification.model.enums.NotificationStatus;
import com.notification.model.enums.RecurrenceType;
import com.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationProcessingService {

    private final NotificationRepository notificationRepository;
    private final ChannelResolverService channelResolverService;
    private final RetryService retryService;

    @Async("notificationTaskExecutor")
    @Transactional
    public CompletableFuture<Void> processNotificationAsync(Notification notification) {
        processNotification(notification);
        return CompletableFuture.completedFuture(null);
    }

    @Transactional
    public void processNotification(Notification notification) {
        try {
            notification.setStatus(NotificationStatus.PROCESSING);
            notificationRepository.save(notification);

            NotificationChannelHandler handler = channelResolverService.resolve(notification.getChannel());
            handler.send(notification);

            notification.setStatus(NotificationStatus.SENT);
            notification.setSentAt(LocalDateTime.now());

            if (notification.getRecurrenceType() != RecurrenceType.NONE) {
                notification.setNextRecurrenceAt(calculateNextRecurrence(
                        LocalDateTime.now(), notification.getRecurrenceType()));
            }

            notificationRepository.save(notification);
            log.info("Notification [{}] sent successfully via {}",
                    notification.getId(), notification.getChannel());

        } catch (Exception e) {
            log.error("Failed to send notification [{}]: {}", notification.getId(), e.getMessage());
            notification.setStatus(NotificationStatus.FAILED);
            notification.setFailureReason(e.getMessage());
            notificationRepository.save(notification);

            retryService.retryNotification(notification);
        }
    }

    public LocalDateTime calculateNextRecurrence(LocalDateTime from, RecurrenceType recurrenceType) {
        return switch (recurrenceType) {
            case DAILY -> from.plusDays(1);
            case WEEKLY -> from.plusWeeks(1);
            case MONTHLY -> from.plusMonths(1);
            case NONE -> null;
        };
    }
}
