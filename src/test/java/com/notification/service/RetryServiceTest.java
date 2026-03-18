package com.notification.service;

import com.notification.channel.NotificationChannelHandler;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RetryServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private ChannelResolverService channelResolverService;

    @Mock
    private NotificationChannelHandler emailHandler;

    @InjectMocks
    private RetryService retryService;

    private User testUser;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(retryService, "maxRetryAttempts", 3);
        ReflectionTestUtils.setField(retryService, "initialDelayMs", 100);
        ReflectionTestUtils.setField(retryService, "multiplier", 2.0);

        testUser = User.builder()
                .id(1L)
                .username("testuser")
                .email("test@example.com")
                .build();
    }

    @Test
    @DisplayName("Should calculate exponential backoff correctly")
    void shouldCalculateExponentialBackoff() {
        assertThat(retryService.calculateExponentialBackoff(0)).isEqualTo(100);  // 100 * 2^0
        assertThat(retryService.calculateExponentialBackoff(1)).isEqualTo(200);  // 100 * 2^1
        assertThat(retryService.calculateExponentialBackoff(2)).isEqualTo(400);  // 100 * 2^2
    }

    // ========== scheduleRetry() tests ==========

    @Test
    @DisplayName("scheduleRetry should set RETRY status and nextRetryAt")
    void scheduleRetryShouldSetRetryStatusAndNextRetryAt() {
        Notification notification = buildNotification(0, 3);
        when(notificationRepository.save(any(Notification.class))).thenReturn(notification);

        retryService.scheduleRetry(notification);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.RETRY);
        assertThat(notification.getNextRetryAt()).isNotNull();
        assertThat(notification.getNextRetryAt()).isAfter(LocalDateTime.now().minusSeconds(1));
        verify(notificationRepository).save(notification);
    }

    @Test
    @DisplayName("scheduleRetry should mark FAILED when max retries reached")
    void scheduleRetryShouldMarkFailedWhenMaxRetriesReached() {
        Notification notification = buildNotification(3, 3);
        when(notificationRepository.save(any(Notification.class))).thenReturn(notification);

        retryService.scheduleRetry(notification);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        verify(notificationRepository).save(notification);
    }

    // ========== executeRetry() tests ==========

    @Test
    @DisplayName("executeRetry should mark SENT on success")
    void executeRetryShouldMarkSentOnSuccess() {
        Notification notification = buildNotification(0, 3);
        when(channelResolverService.resolve(NotificationChannel.EMAIL)).thenReturn(emailHandler);
        doNothing().when(emailHandler).send(any(Notification.class));
        when(notificationRepository.save(any(Notification.class))).thenReturn(notification);

        retryService.executeRetry(notification);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getRetryCount()).isEqualTo(1);
        assertThat(notification.getNextRetryAt()).isNull();
        verify(notificationRepository).save(notification);
    }

    @Test
    @DisplayName("executeRetry should mark FAILED after max retries on failure")
    void executeRetryShouldMarkFailedAfterMaxRetries() {
        Notification notification = buildNotification(2, 3);
        when(channelResolverService.resolve(NotificationChannel.EMAIL)).thenReturn(emailHandler);
        doThrow(new RuntimeException("Send failed")).when(emailHandler).send(any(Notification.class));
        when(notificationRepository.save(any(Notification.class))).thenReturn(notification);

        retryService.executeRetry(notification);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getRetryCount()).isEqualTo(3);
        assertThat(notification.getFailureReason()).isEqualTo("Send failed");
        assertThat(notification.getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("executeRetry should schedule next retry on intermediate failure")
    void executeRetryShouldScheduleNextRetryOnIntermediateFailure() {
        Notification notification = buildNotification(0, 3);
        when(channelResolverService.resolve(NotificationChannel.EMAIL)).thenReturn(emailHandler);
        doThrow(new RuntimeException("Temporary failure")).when(emailHandler).send(any(Notification.class));
        when(notificationRepository.save(any(Notification.class))).thenReturn(notification);

        retryService.executeRetry(notification);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.RETRY);
        assertThat(notification.getRetryCount()).isEqualTo(1);
        assertThat(notification.getNextRetryAt()).isNotNull();
    }

    // ========== processDueRetries() tests ==========

    @Test
    @DisplayName("processDueRetries should process due notifications")
    void processDueRetriesShouldProcessDueNotifications() {
        Notification notification = buildNotification(0, 3);
        when(notificationRepository.findDueRetryNotifications(any(LocalDateTime.class)))
                .thenReturn(List.of(notification));
        when(channelResolverService.resolve(NotificationChannel.EMAIL)).thenReturn(emailHandler);
        doNothing().when(emailHandler).send(any(Notification.class));
        when(notificationRepository.save(any(Notification.class))).thenReturn(notification);

        retryService.processDueRetries();

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        verify(notificationRepository).save(notification);
    }

    @Test
    @DisplayName("processDueRetries should do nothing when no due retries")
    void processDueRetriesShouldDoNothingWhenEmpty() {
        when(notificationRepository.findDueRetryNotifications(any(LocalDateTime.class)))
                .thenReturn(List.of());

        retryService.processDueRetries();

        verify(notificationRepository, never()).save(any());
    }

    // ========== helper ==========

    private Notification buildNotification(int retryCount, int maxRetries) {
        return Notification.builder()
                .id(1L)
                .user(testUser)
                .title("Test")
                .message("Test message")
                .channel(NotificationChannel.EMAIL)
                .priority(NotificationPriority.HIGH)
                .status(NotificationStatus.FAILED)
                .recurrenceType(RecurrenceType.NONE)
                .retryCount(retryCount)
                .maxRetries(maxRetries)
                .build();
    }
}
