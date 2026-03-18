package com.notification.channel;

import com.notification.model.Notification;
import com.notification.model.enums.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class EmailChannelHandler implements NotificationChannelHandler {

    @Override
    public void send(Notification notification) {
        log.info("Sending EMAIL to user [{}] at [{}]: subject='{}', body='{}'",
                notification.getUser().getId(),
                notification.getUser().getEmail(),
                notification.getTitle(),
                notification.getMessage());

        // In production, integrate with an email service (e.g., SendGrid, AWS SES)
        simulateEmailSend(notification);

        log.info("EMAIL sent successfully for notification [{}]", notification.getId());
    }

    @Override
    public NotificationChannel getChannel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public boolean supports(NotificationChannel channel) {
        return NotificationChannel.EMAIL.equals(channel);
    }

    private void simulateEmailSend(Notification notification) {
        if (notification.getUser().getEmail() == null || notification.getUser().getEmail().isBlank()) {
            throw new RuntimeException("User does not have a valid email address");
        }
        // Simulate external API latency (e.g., SendGrid/SES network call)
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
