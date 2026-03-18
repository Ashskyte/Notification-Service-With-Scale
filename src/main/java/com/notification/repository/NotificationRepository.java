package com.notification.repository;

import com.notification.model.Notification;
import com.notification.model.enums.NotificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByUserIdAndStatus(Long userId, NotificationStatus status);

    Page<Notification> findByUserId(Long userId, Pageable pageable);

    @Query("SELECT n FROM Notification n WHERE n.status = :status AND n.scheduledAt <= :now ORDER BY n.priority ASC, n.createdAt ASC")
    List<Notification> findScheduledNotificationsReady(
            @Param("status") NotificationStatus status,
            @Param("now") LocalDateTime now);

    @Query("SELECT n FROM Notification n WHERE n.status = 'PENDING' ORDER BY n.priority ASC, n.createdAt ASC")
    List<Notification> findPendingNotificationsByPriority(Pageable pageable);

    @Query("SELECT n FROM Notification n WHERE n.status = :status AND n.retryCount < n.maxRetries")
    List<Notification> findRetryableNotifications(@Param("status") NotificationStatus status);

    @Query("SELECT n FROM Notification n WHERE n.status = 'RETRY' AND n.retryCount < n.maxRetries AND n.nextRetryAt <= :now ORDER BY n.nextRetryAt ASC")
    List<Notification> findDueRetryNotifications(@Param("now") LocalDateTime now);

    @Query("SELECT n FROM Notification n WHERE n.recurrenceType <> 'NONE' AND n.nextRecurrenceAt <= :now AND n.status = 'SENT'")
    List<Notification> findRecurringNotificationsDue(@Param("now") LocalDateTime now);

    List<Notification> findByUserIdAndStatusIn(Long userId, List<NotificationStatus> statuses);

    long countByStatus(NotificationStatus status);
}
