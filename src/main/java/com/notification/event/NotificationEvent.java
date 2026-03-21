package com.notification.event;

import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class NotificationEvent implements Serializable {

    private Long notificationId;
    private String eventType;
    private LocalDateTime timestamp;

    public static NotificationEvent ofSend(Long notificationId) {
        return NotificationEvent.builder()
                .notificationId(notificationId)
                .eventType("SEND")
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static NotificationEvent ofRetry(Long notificationId) {
        return NotificationEvent.builder()
                .notificationId(notificationId)
                .eventType("RETRY")
                .timestamp(LocalDateTime.now())
                .build();
    }
}
