package com.notification.channel;

import com.notification.model.Notification;
import com.notification.model.enums.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PushNotificationChannelHandler implements NotificationChannelHandler {

    @Override
    public void send(Notification notification) {
        log.info("Sending PUSH NOTIFICATION to user [{}] with device token [{}]: title='{}', body='{}'",
                notification.getUser().getId(),
                notification.getUser().getDeviceToken(),
                notification.getTitle(),
                notification.getMessage());

        // In production, integrate with Firebase Cloud Messaging (FCM) or Apple Push Notification Service (APNs)
        simulatePushSend(notification);

        log.info("PUSH NOTIFICATION sent successfully for notification [{}]", notification.getId());
    }

    @Override
    public NotificationChannel getChannel() {
        return NotificationChannel.PUSH_NOTIFICATION;
    }

    @Override
    public boolean supports(NotificationChannel channel) {
        return NotificationChannel.PUSH_NOTIFICATION.equals(channel);
    }

    private void simulatePushSend(Notification notification) {
        if (notification.getUser().getDeviceToken() == null || notification.getUser().getDeviceToken().isBlank()) {
            throw new RuntimeException("User does not have a valid device token");
        }
        // Simulate external API latency (e.g., FCM/APNs network call)
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
