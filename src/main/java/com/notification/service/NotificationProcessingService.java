package com.notification.service;
import com.notification.channel.NotificationChannelHandler;
import com.notification.model.Notification;
import com.notification.model.enums.NotificationStatus;
import com.notification.model.enums.RecurrenceType;
import com.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
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
    private final RateLimitService rateLimitService;
    private final CacheManager cacheManager;
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
            // Rate limit check: throttle per channel per user
            if (!rateLimitService.isAllowed(notification.getChannel(), notification.getUser().getId())) {
                log.warn("Notification [{}] rate limited for channel {} and user {}",
                        notification.getId(), notification.getChannel(), notification.getUser().getId());
                notification.setStatus(NotificationStatus.RATE_LIMITED);
                notificationRepository.save(notification);
                retryService.scheduleRetry(notification);
                evictNotificationCache(notification.getId());
                return;
            }
            NotificationChannelHandler handler = channelResolverService.resolve(notification.getChannel());
            handler.send(notification);
            notification.setStatus(NotificationStatus.SENT);
            notification.setSentAt(LocalDateTime.now());
            if (notification.getRecurrenceType() != RecurrenceType.NONE) {
                notification.setNextRecurrenceAt(calculateNextRecurrence(
                        LocalDateTime.now(), notification.getRecurrenceType()));
            }
            notificationRepository.save(notification);
            evictNotificationCache(notification.getId());
            log.info("Notification [{}] sent successfully via {}",
                    notification.getId(), notification.getChannel());
        } catch (Exception e) {
            log.error("Failed to send notification [{}]: {}", notification.getId(), e.getMessage());
            notification.setStatus(NotificationStatus.FAILED);
            notification.setFailureReason(e.getMessage());
            notificationRepository.save(notification);
            evictNotificationCache(notification.getId());
            retryService.scheduleRetry(notification);
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
    /**
     * Evict cached notification after status change so read endpoints return fresh data.
     */
    private void evictNotificationCache(Long notificationId) {
        try {
            var cache = cacheManager.getCache("notifications");
            if (cache != null) {
                cache.evict(notificationId);
            }
        } catch (Exception e) {
            log.debug("Failed to evict notification cache for id {}: {}", notificationId, e.getMessage());
        }
    }
}