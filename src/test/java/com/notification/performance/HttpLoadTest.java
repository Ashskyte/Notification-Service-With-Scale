package com.notification.performance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone HTTP Load Test for Notification System.
 * Tests against a running server by sending concurrent HTTP requests.
 *
 * Usage: Run as a JUnit test or standalone main method.
 * Requires the server to be running on the configured BASE_URL.
 */
public class HttpLoadTest {

    private static final String BASE_URL = "http://localhost:8081";
    private static final int NUM_USERS = 20;
    private static final int TOTAL_NOTIFICATIONS = 1000;
    private static final int CONCURRENT_THREADS = 30;

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final List<Long> userIds = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        String label = args.length > 0 ? args[0] : "LOAD TEST";

        System.out.println("==========================================================");
        System.out.println("  HTTP LOAD TEST: " + label);
        System.out.println("  Target: " + BASE_URL);
        System.out.println("  Notifications: " + TOTAL_NOTIFICATIONS);
        System.out.println("  Concurrent threads: " + CONCURRENT_THREADS);
        System.out.println("  Simulated API latency: 100ms per channel handler");
        System.out.println("==========================================================");

        // Step 1: Seed users
        System.out.println("\n[1/3] Seeding " + NUM_USERS + " test users...");
        seedUsers();
        System.out.println("  Created " + userIds.size() + " users: " + userIds);

        if (userIds.isEmpty()) {
            System.err.println("ERROR: No users created. Is the server running on " + BASE_URL + "?");
            System.exit(1);
        }

        // Step 2: Warm up (5 requests to prime connection pool & JIT)
        System.out.println("\n[2/3] Warming up with 5 requests...");
        for (int i = 0; i < 5; i++) {
            sendNotification(userIds.get(i % userIds.size()), "Warmup #" + i);
        }
        Thread.sleep(500);

        // Step 3: Load test
        System.out.println("\n[3/3] Firing " + TOTAL_NOTIFICATIONS + " concurrent notifications...\n");

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(TOTAL_NOTIFICATIONS);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        long overallStart = System.currentTimeMillis();

        for (int i = 0; i < TOTAL_NOTIFICATIONS; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    Long userId = userIds.get(index % userIds.size());
                    long start = System.currentTimeMillis();

                    HttpResponse<String> response = sendNotification(userId, "Load Test #" + index);
                    long elapsed = System.currentTimeMillis() - start;

                    latencies.add(elapsed);
                    statusCodes.add(response.statusCode());

                    if (response.statusCode() == 200 || response.statusCode() == 201) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                        System.err.println("  [FAIL] #" + index + " status=" + response.statusCode()
                                + " body=" + response.body().substring(0, Math.min(100, response.body().length())));
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    System.err.println("  [ERROR] #" + index + " " + e.getMessage());
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
        double throughput = (successCount.get() * 1000.0) / totalTimeMs;

        // Print results
        System.out.println("==========================================================");
        System.out.println("  HTTP LOAD TEST RESULTS: " + label);
        System.out.println("==========================================================");
        System.out.println("  Total requests         : " + TOTAL_NOTIFICATIONS);
        System.out.println("  Successful (2xx)       : " + successCount.get());
        System.out.println("  Failed                 : " + failCount.get());
        System.out.println("  Total time             : " + totalTimeMs + " ms");
        System.out.printf("  Throughput             : %.2f requests/sec%n", throughput);
        System.out.println("  ---");
        System.out.printf("  Avg latency            : %.2f ms%n", avgLatency);
        System.out.println("  Min latency            : " + minLatency + " ms");
        System.out.println("  Max latency            : " + maxLatency + " ms");
        System.out.println("  P50 (median)           : " + p50 + " ms");
        System.out.println("  P95                    : " + p95 + " ms");
        System.out.println("  P99                    : " + p99 + " ms");
        System.out.println("==========================================================");
    }

    private static void seedUsers() {
        for (int i = 1; i <= NUM_USERS; i++) {
            try {
                String uniqueSuffix = String.valueOf(System.nanoTime());
                String json = "{" +
                        "\"username\": \"httpuser_" + i + "_" + uniqueSuffix + "\"," +
                        "\"email\": \"httpuser" + i + "_" + uniqueSuffix + "@test.com\"," +
                        "\"phoneNumber\": \"+120000000" + i + "\"," +
                        "\"deviceToken\": \"device-http-" + i + "\"," +
                        "\"preferredChannels\": [\"EMAIL\"]" +
                        "}";

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/api/users"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .timeout(Duration.ofSeconds(10))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200 || response.statusCode() == 201) {
                    // Extract id from JSON response (simple parsing)
                    String body = response.body();
                    Long id = extractId(body);
                    if (id != null) {
                        userIds.add(id);
                    }
                } else {
                    System.err.println("  User creation failed: " + response.statusCode() + " " + response.body());
                }
            } catch (Exception e) {
                System.err.println("  User creation error: " + e.getMessage());
            }
        }
    }

    private static HttpResponse<String> sendNotification(Long userId, String title) throws Exception {
        String json = "{" +
                "\"userId\": " + userId + "," +
                "\"title\": \"" + title + "\"," +
                "\"message\": \"HTTP load test notification\"," +
                "\"channel\": \"EMAIL\"," +
                "\"priority\": \"MEDIUM\"" +
                "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/notifications/send"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(30))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Long extractId(String json) {
        try {
            // Simple extraction: find "id": <number>
            int idx = json.indexOf("\"id\"");
            if (idx == -1) return null;
            int colon = json.indexOf(":", idx);
            int start = colon + 1;
            while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) start++;
            int end = start;
            while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
            return Long.parseLong(json.substring(start, end));
        } catch (Exception e) {
            return null;
        }
    }

    private static long percentile(List<Long> sortedList, int percentile) {
        if (sortedList.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile / 100.0 * sortedList.size()) - 1;
        return sortedList.get(Math.max(0, Math.min(index, sortedList.size() - 1)));
    }
}
