package com.notification.channel;

import com.notification.model.Notification;
import com.notification.model.enums.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SmsChannelHandler implements NotificationChannelHandler {

    @Override
    public void send(Notification notification) {
        log.info("Sending SMS to user [{}] at [{}]: message='{}'",
                notification.getUser().getId(),
                notification.getUser().getPhoneNumber(),
                notification.getMessage());

        // In production, integrate with an SMS service (e.g., Twilio, AWS SNS)
        simulateSmsSend(notification);

        log.info("SMS sent successfully for notification [{}]", notification.getId());
    }

    @Override
    public NotificationChannel getChannel() {
        return NotificationChannel.SMS;
    }

    @Override
    public boolean supports(NotificationChannel channel) {
        return NotificationChannel.SMS.equals(channel);
    }

    private void simulateSmsSend(Notification notification) {
        if (notification.getUser().getPhoneNumber() == null || notification.getUser().getPhoneNumber().isBlank()) {
            throw new RuntimeException("User does not have a valid phone number");
        }
        // Simulate external API latency (e.g., Twilio/SNS network call)
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
