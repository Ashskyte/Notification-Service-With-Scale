package com.notification.kafka;

import com.notification.event.NotificationEvent;
import com.notification.model.Notification;
import com.notification.repository.NotificationRepository;
import com.notification.service.NotificationProcessingService;
import com.notification.service.RetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationKafkaConsumer {

    private final NotificationRepository notificationRepository;
    private final NotificationProcessingService processingService;
    private final RetryService retryService;

    @KafkaListener(
            topics = "${notification.kafka.topic.send:notification-send}",
            groupId = "${notification.kafka.consumer.group-id:notification-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeSendEvent(NotificationEvent event) {
        log.info("Consumed SEND event for notification [{}] from topic [notification-send]",
                event.getNotificationId());

        Notification notification = notificationRepository.findById(event.getNotificationId())
                .orElseThrow(() -> {
                    log.error("Notification [{}] not found in DB — skipping", event.getNotificationId());
                    return new RuntimeException("Notification not found: " + event.getNotificationId());
                });

        processingService.processNotification(notification);
    }

    @KafkaListener(
            topics = "${notification.kafka.topic.retry:notification-retry}",
            groupId = "${notification.kafka.consumer.group-id:notification-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeRetryEvent(NotificationEvent event) {
        log.info("Consumed RETRY event for notification [{}] from topic [notification-retry]",
                event.getNotificationId());

        Notification notification = notificationRepository.findById(event.getNotificationId())
                .orElseThrow(() -> {
                    log.error("Notification [{}] not found in DB for retry — skipping", event.getNotificationId());
                    return new RuntimeException("Notification not found: " + event.getNotificationId());
                });

        retryService.executeRetry(notification);
    }

    @KafkaListener(
            topics = "${notification.kafka.topic.send:notification-send}.DLT",
            groupId = "${notification.kafka.consumer.group-id:notification-group}-dlt"
    )
    public void consumeSendDlt(NotificationEvent event) {
        log.error("DLT: Notification [{}] failed all Kafka retry attempts on send topic. " +
                "Manual intervention required.", event.getNotificationId());
    }

    @KafkaListener(
            topics = "${notification.kafka.topic.retry:notification-retry}.DLT",
            groupId = "${notification.kafka.consumer.group-id:notification-group}-dlt"
    )
    public void consumeRetryDlt(NotificationEvent event) {
        log.error("DLT: Notification [{}] failed all Kafka retry attempts on retry topic. " +
                "Manual intervention required.", event.getNotificationId());
    }
}
