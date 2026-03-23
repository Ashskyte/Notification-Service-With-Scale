package com.notification.performance;

import com.notification.dto.NotificationResponse;
import com.notification.dto.SendNotificationRequest;
import com.notification.dto.UserRegistrationRequest;
import com.notification.model.enums.NotificationChannel;
import com.notification.model.enums.NotificationPriority;
import com.notification.service.NotificationService;
import com.notification.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest
public class PerformanceLoadTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private UserService userService;

    private static final int NUM_USERS = 10;
    private static final int TOTAL_NOTIFICATIONS = 100;
    private static final int CONCURRENT_THREADS = 20;

    private List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        userIds = new ArrayList<>();
        // Seed test users
        for (int i = 1; i <= NUM_USERS; i++) {
            try {
                long unique = System.nanoTime();
                var request = UserRegistrationRequest.builder()
                        .username("perfuser_" + i + "_" + unique)
                        .email("perfuser" + i + "_" + unique + "@test.com")
                        .phoneNumber("+1" + unique)
                        .deviceToken("device-token-perf-" + i + "-" + unique)
                        .preferredChannels(Arrays.asList(NotificationChannel.EMAIL))
                        .build();
                var user = userService.registerUser(request);
                userIds.add(user.getId());
            } catch (Exception e) {
                log.warn("User creation failed (may already exist): {}", e.getMessage());
            }
        }
        assertThat(userIds).isNotEmpty();
        log.info("Seeded {} test users: {}", userIds.size(), userIds);
    }

    @Test
    @DisplayName("SYNC - Load Test: Send notifications concurrently and measure performance")
    void loadTestSendNotifications() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(TOTAL_NOTIFICATIONS);
        int successCount[] = {0};
        int failCount[] = {0};

        log.info("==========================================================");
        log.info("  SYNC PERFORMANCE LOAD TEST");
        log.info("  Notifications: {}, Threads: {}, Users: {}",
                TOTAL_NOTIFICATIONS, CONCURRENT_THREADS, NUM_USERS);
        log.info("  Channel: EMAIL, Simulated Latency: 100ms per send");
        log.info("==========================================================");

        long overallStart = System.currentTimeMillis();

        for (int i = 0; i < TOTAL_NOTIFICATIONS; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    Long userId = userIds.get(index % userIds.size());

                    SendNotificationRequest request = SendNotificationRequest.builder()
                            .userId(userId)
                            .title("Load Test #" + index + "-" + System.nanoTime())
                            .message("Performance test notification " + index + "-" + System.nanoTime())
                            .channel(NotificationChannel.EMAIL)
                            .priority(NotificationPriority.MEDIUM)
                            .build();

                    long start = System.currentTimeMillis();
                    NotificationResponse response = notificationService.sendNotification(request);
                    long elapsed = System.currentTimeMillis() - start;

                    latencies.add(elapsed);
                    synchronized (successCount) { successCount[0]++; }

                } catch (Exception e) {
                    log.error("Notification {} failed: {}", index, e.getMessage());
                    synchronized (failCount) { failCount[0]++; }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.MINUTES);
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);

        long overallEnd = System.currentTimeMillis();
        long totalTimeMs = overallEnd - overallStart;

        // Calculate metrics
        Collections.sort(latencies);
        double avgLatency = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        long minLatency = latencies.isEmpty() ? 0 : latencies.get(0);
        long maxLatency = latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1);
        long p50 = percentile(latencies, 50);
        long p95 = percentile(latencies, 95);
        long p99 = percentile(latencies, 99);
        double throughput = (successCount[0] * 1000.0) / totalTimeMs;

        log.info("==========================================================");
        log.info("  SYNC PERFORMANCE RESULTS");
        log.info("==========================================================");
        log.info("  Total notifications sent : {}", successCount[0]);
        log.info("  Failed notifications     : {}", failCount[0]);
        log.info("  Total time               : {} ms", totalTimeMs);
        log.info("  Throughput               : {} notifications/sec", String.format("%.2f", throughput));
        log.info("  ---");
        log.info("  Avg latency (per call)   : {} ms", String.format("%.2f", avgLatency));
        log.info("  Min latency              : {} ms", minLatency);
        log.info("  Max latency              : {} ms", maxLatency);
        log.info("  P50 (median)             : {} ms", p50);
        log.info("  P95                      : {} ms", p95);
        log.info("  P99                      : {} ms", p99);
        log.info("==========================================================");

        // Assertions
        assertThat(successCount[0]).isEqualTo(TOTAL_NOTIFICATIONS);
        assertThat(failCount[0]).isZero();
    }

    private long percentile(List<Long> sortedList, int percentile) {
        if (sortedList.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile / 100.0 * sortedList.size()) - 1;
        return sortedList.get(Math.max(0, Math.min(index, sortedList.size() - 1)));
    }
}
