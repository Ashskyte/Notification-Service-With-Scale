package com.notification.service;

import com.notification.dto.NotificationResponse;
import com.notification.dto.SendNotificationRequest;
import com.notification.event.NotificationEvent;
import com.notification.exception.NotificationException;
import com.notification.kafka.NotificationKafkaProducer;
import com.notification.model.Notification;
import com.notification.model.User;
import com.notification.model.enums.*;
import com.notification.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserService userService;

    @Mock
    private NotificationKafkaProducer kafkaProducer;

    @InjectMocks
    private NotificationService notificationService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(1L)
                .username("testuser")
                .email("test@example.com")
                .phoneNumber("+1234567890")
                .deviceToken("device-token-123")
                .build();
    }

    @Test
    @DisplayName("Should send immediate notification with HIGH priority")
    void shouldSendImmediateNotificationWithHighPriority() {
        SendNotificationRequest request = SendNotificationRequest.builder()
                .userId(1L)
                .title("Urgent Alert")
                .message("This is a high priority notification")
                .channel(NotificationChannel.EMAIL)
                .priority(NotificationPriority.HIGH)
                .build();

        when(userService.findUserById(1L)).thenReturn(testUser);
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> {
                    Notification n = invocation.getArgument(0);
                    n.setId(1L);
                    n.setCreatedAt(LocalDateTime.now());
                    return n;
                });
        NotificationResponse response = notificationService.sendNotification(request);

        assertThat(response).isNotNull();
        assertThat(response.getPriority()).isEqualTo(NotificationPriority.HIGH);
        assertThat(response.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        verify(kafkaProducer).sendNotificationEvent(any(NotificationEvent.class));
    }

    @Test
    @DisplayName("Should default to MEDIUM priority when not specified")
    void shouldDefaultToMediumPriority() {
        SendNotificationRequest request = SendNotificationRequest.builder()
                .userId(1L)
                .title("Regular Update")
                .message("This is a regular notification")
                .channel(NotificationChannel.EMAIL)
                .build();

        when(userService.findUserById(1L)).thenReturn(testUser);
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> {
                    Notification n = invocation.getArgument(0);
                    n.setId(2L);
                    n.setCreatedAt(LocalDateTime.now());
                    return n;
                });
        NotificationResponse response = notificationService.sendNotification(request);

        assertThat(response.getPriority()).isEqualTo(NotificationPriority.MEDIUM);
    }

    @Test
    @DisplayName("Should schedule notification for future delivery")
    void shouldScheduleNotification() {
        LocalDateTime futureTime = LocalDateTime.now().plusHours(2);

        SendNotificationRequest request = SendNotificationRequest.builder()
                .userId(1L)
                .title("Scheduled Alert")
                .message("This is a scheduled notification")
                .channel(NotificationChannel.SMS)
                .priority(NotificationPriority.LOW)
                .scheduledAt(futureTime)
                .build();

        when(userService.findUserById(1L)).thenReturn(testUser);
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> {
                    Notification n = invocation.getArgument(0);
                    n.setId(3L);
                    n.setCreatedAt(LocalDateTime.now());
                    return n;
                });

        NotificationResponse response = notificationService.sendNotification(request);

        assertThat(response.getStatus()).isEqualTo(NotificationStatus.SCHEDULED);
        assertThat(response.getScheduledAt()).isEqualTo(futureTime);
        verify(kafkaProducer, never()).sendNotificationEvent(any());
    }

    @Test
    @DisplayName("Should reject scheduled time in the past")
    void shouldRejectPastScheduledTime() {
        LocalDateTime pastTime = LocalDateTime.now().minusHours(1);

        SendNotificationRequest request = SendNotificationRequest.builder()
                .userId(1L)
                .title("Past Alert")
                .message("This should fail")
                .channel(NotificationChannel.EMAIL)
                .scheduledAt(pastTime)
                .build();

        when(userService.findUserById(1L)).thenReturn(testUser);

        assertThatThrownBy(() -> notificationService.sendNotification(request))
                .isInstanceOf(NotificationException.class)
                .hasMessageContaining("Scheduled time must be in the future");
    }

    @Test
    @DisplayName("Should retrieve notification by ID")
    void shouldRetrieveNotificationById() {
        Notification notification = Notification.builder()
                .id(1L)
                .user(testUser)
                .title("Test")
                .message("Test message")
                .channel(NotificationChannel.EMAIL)
                .priority(NotificationPriority.HIGH)
                .status(NotificationStatus.SENT)
                .recurrenceType(RecurrenceType.NONE)
                .build();
        notification.setCreatedAt(LocalDateTime.now());

        when(notificationRepository.findById(1L)).thenReturn(Optional.of(notification));

        NotificationResponse response = notificationService.getNotificationById(1L);

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    @DisplayName("Should throw exception for non-existent notification")
    void shouldThrowForNonExistentNotification() {
        when(notificationRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.getNotificationById(999L))
                .isInstanceOf(NotificationException.class)
                .hasMessageContaining("Notification not found");
    }

    @Test
    @DisplayName("Should calculate next recurrence correctly")
    void shouldCalculateNextRecurrence() {
        LocalDateTime now = LocalDateTime.of(2025, 3, 18, 10, 0);

        NotificationProcessingService realProcessingService = new NotificationProcessingService(
                notificationRepository, null, null);

        assertThat(realProcessingService.calculateNextRecurrence(now, RecurrenceType.DAILY))
                .isEqualTo(now.plusDays(1));
        assertThat(realProcessingService.calculateNextRecurrence(now, RecurrenceType.WEEKLY))
                .isEqualTo(now.plusWeeks(1));
        assertThat(realProcessingService.calculateNextRecurrence(now, RecurrenceType.MONTHLY))
                .isEqualTo(now.plusMonths(1));
        assertThat(realProcessingService.calculateNextRecurrence(now, RecurrenceType.NONE))
                .isNull();
    }
}
