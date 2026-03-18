package com.notification.service;

import com.notification.channel.NotificationChannelHandler;
import com.notification.model.Notification;
import com.notification.model.enums.NotificationStatus;
import com.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetryService {

    private final NotificationRepository notificationRepository;
    private final ChannelResolverService channelResolverService;

    @Value("${notification.retry.max-attempts:3}")
    private int maxRetryAttempts;

    @Value("${notification.retry.initial-delay-ms:1000}")
    private long initialDelayMs;

    @Value("${notification.retry.multiplier:2.0}")
    private double multiplier;

    /**
     * Called immediately when a notification fails during processing.
     * Instead of Thread.sleep, this sets status=RETRY and calculates nextRetryAt
     * so the scheduler can pick it up later without blocking any thread.
     */
    @Transactional
    public void scheduleRetry(Notification notification) {
        if (notification.getRetryCount() >= notification.getMaxRetries()) {
            log.warn("Notification [{}] exceeded max retries ({}). Marking as permanently FAILED.",
                    notification.getId(), notification.getMaxRetries());
            notification.setStatus(NotificationStatus.FAILED);
            notificationRepository.save(notification);
            return;
        }

        long delayMs = calculateExponentialBackoff(notification.getRetryCount());
        LocalDateTime nextRetryAt = LocalDateTime.now().plusNanos(delayMs * 1_000_000);

        notification.setStatus(NotificationStatus.RETRY);
        notification.setNextRetryAt(nextRetryAt);
        notificationRepository.save(notification);

        log.info("Notification [{}] scheduled for retry attempt {}/{} at {} (delay={}ms)",
                notification.getId(),
                notification.getRetryCount() + 1,
                notification.getMaxRetries(),
                nextRetryAt,
                delayMs);
    }

    /**
     * Called by the scheduler to process notifications whose retry time has arrived.
     * Finds all RETRY notifications where nextRetryAt <= now and attempts to send them.
     */
    @Transactional
    public void processDueRetries() {
        List<Notification> dueRetries = notificationRepository.findDueRetryNotifications(LocalDateTime.now());

        if (dueRetries.isEmpty()) {
            return;
        }

        log.info("Found {} notifications due for retry", dueRetries.size());

        for (Notification notification : dueRetries) {
            executeRetry(notification);
        }
    }

    /**
     * Attempts to send a single notification that is due for retry.
     * On success: marks as SENT. On failure: either schedules another retry or marks permanently FAILED.
     */
    @Transactional
    public void executeRetry(Notification notification) {
        notification.setRetryCount(notification.getRetryCount() + 1);

        log.info("Executing retry for notification [{}], attempt {}/{}",
                notification.getId(),
                notification.getRetryCount(),
                notification.getMaxRetries());

        try {
            NotificationChannelHandler handler = channelResolverService.resolve(notification.getChannel());
            handler.send(notification);

            notification.setStatus(NotificationStatus.SENT);
            notification.setSentAt(LocalDateTime.now());
            notification.setNextRetryAt(null);
            log.info("Notification [{}] sent successfully on retry attempt {}",
                    notification.getId(), notification.getRetryCount());

        } catch (Exception e) {
            notification.setFailureReason(e.getMessage());

            if (notification.getRetryCount() >= notification.getMaxRetries()) {
                notification.setStatus(NotificationStatus.FAILED);
                notification.setNextRetryAt(null);
                log.error("Notification [{}] permanently failed after {} retries: {}",
                        notification.getId(), notification.getRetryCount(), e.getMessage());
            } else {
                long delayMs = calculateExponentialBackoff(notification.getRetryCount());
                notification.setNextRetryAt(LocalDateTime.now().plusNanos(delayMs * 1_000_000));
                notification.setStatus(NotificationStatus.RETRY);
                log.warn("Notification [{}] retry attempt {} failed, next retry at {} (delay={}ms): {}",
                        notification.getId(), notification.getRetryCount(),
                        notification.getNextRetryAt(), delayMs, e.getMessage());
            }
        }

        notificationRepository.save(notification);
    }

    public long calculateExponentialBackoff(int retryCount) {
        return (long) (initialDelayMs * Math.pow(multiplier, retryCount));
    }
}
