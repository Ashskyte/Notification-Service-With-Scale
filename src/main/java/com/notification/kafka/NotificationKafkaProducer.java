package com.notification.kafka;

import com.notification.event.NotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationKafkaProducer {

    private final KafkaTemplate<String, NotificationEvent> kafkaTemplate;

    @Value("${notification.kafka.topic.send:notification-send}")
    private String sendTopic;

    @Value("${notification.kafka.topic.retry:notification-retry}")
    private String retryTopic;

    public void sendNotificationEvent(NotificationEvent event) {
        String key = String.valueOf(event.getNotificationId());

        CompletableFuture<SendResult<String, NotificationEvent>> future =
                kafkaTemplate.send(sendTopic, key, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish notification event [{}] to topic [{}]: {}",
                        event.getNotificationId(), sendTopic, ex.getMessage());
            } else {
                log.info("Published notification event [{}] to topic [{}], partition={}, offset={}",
                        event.getNotificationId(), sendTopic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }

    public void sendRetryEvent(NotificationEvent event) {
        String key = String.valueOf(event.getNotificationId());

        CompletableFuture<SendResult<String, NotificationEvent>> future =
                kafkaTemplate.send(retryTopic, key, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish retry event [{}] to topic [{}]: {}",
                        event.getNotificationId(), retryTopic, ex.getMessage());
            } else {
                log.info("Published retry event [{}] to topic [{}], partition={}, offset={}",
                        event.getNotificationId(), retryTopic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }
}
