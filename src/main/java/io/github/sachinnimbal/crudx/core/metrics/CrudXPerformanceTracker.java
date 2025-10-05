package io.github.sachinnimbal.crudx.core.metrics;

import io.github.sachinnimbal.crudx.core.config.CrudXPerformanceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;


/**
 * @author Sachin Nimbal
 * @version 1.0.0
 * @since 2025
 * @Contact: <a href="mailto:sachinnimbal9@gmail.com">sachinnimbal9@gmail.com</a>
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "crudx.performance", name = "enabled", havingValue = "true")
public class CrudXPerformanceTracker {

    private final CrudXPerformanceProperties properties;
    private final Deque<PerformanceMetric> metrics;
    private final LocalDateTime startTime;
    private final Runtime runtime;

    public CrudXPerformanceTracker(CrudXPerformanceProperties properties) {
        this.properties = properties;
        this.metrics = new ConcurrentLinkedDeque<>();
        this.startTime = LocalDateTime.now();
        this.runtime = Runtime.getRuntime();

        log.info("CrudX Performance Monitoring ENABLED");
        log.info("Dashboard: {}", properties.isDashboardEnabled() ?
                "Enabled at " + properties.getDashboardPath() : "Disabled");
        log.info("Memory Tracking: {}", properties.isTrackMemory() ? "Enabled" : "Disabled");
        log.info("Max Stored Metrics: {}", properties.getMaxStoredMetrics());
        log.info("Retention Period: {} minutes", properties.getRetentionMinutes());
    }

    public void recordMetric(String endpoint, String method, String entityName,
                             long executionTimeMs, boolean success, String errorType,
                             Long memoryDeltaKb) {

        PerformanceMetric metric = PerformanceMetric.builder()
                .endpoint(endpoint)
                .method(method)
                .entityName(entityName)
                .executionTimeMs(executionTimeMs)
                .timestamp(LocalDateTime.now())
                .success(success)
                .errorType(errorType)
                .build();

        // Track memory delta (per-request allocation)
        if (properties.isTrackMemory() && memoryDeltaKb != null) {
            metric.setMemoryUsedKb(memoryDeltaKb);
            metric.setMemoryUsedFormatted(metric.getFormattedMemory());
        }

        metrics.addLast(metric);

        while (metrics.size() > properties.getMaxStoredMetrics()) {
            metrics.removeFirst();
        }
    }

    // Backward compatibility
    public void recordMetric(String endpoint, String method, String entityName,
                             long executionTimeMs, boolean success, String errorType) {
        recordMetric(endpoint, method, entityName, executionTimeMs, success, errorType, null);
    }

    public List<PerformanceMetric> getMetrics() {
        return new ArrayList<>(metrics);
    }

    public List<PerformanceMetric> getMetricsByEndpoint(String endpoint) {
        return metrics.stream()
                .filter(m -> m.getEndpoint().equals(endpoint))
                .collect(Collectors.toList());
    }

    public PerformanceSummary getSummary() {
        List<PerformanceMetric> metricsList = new ArrayList<>(metrics);

        if (metricsList.isEmpty()) {
            return PerformanceSummary.builder()
                    .totalRequests(0L)
                    .monitoringStartTime(startTime)
                    .build();
        }

        long total = metricsList.size();
        long successful = metricsList.stream().filter(PerformanceMetric::isSuccess).count();
        long failed = total - successful;
        double successRate = (double) successful / total * 100;

        long totalTime = metricsList.stream()
                .mapToLong(PerformanceMetric::getExecutionTimeMs)
                .sum();

        double avgTime = (double) totalTime / total;

        long minTime = metricsList.stream()
                .mapToLong(PerformanceMetric::getExecutionTimeMs)
                .min()
                .orElse(0);

        long maxTime = metricsList.stream()
                .mapToLong(PerformanceMetric::getExecutionTimeMs)
                .max()
                .orElse(0);

        // Memory calculations
        Long avgMemoryKb = null;
        Long minMemoryKb = null;
        Long maxMemoryKb = null;
        Long totalMemoryKb = null;

        List<PerformanceMetric> metricsWithMemory = metricsList.stream()
                .filter(m -> m.getMemoryUsedKb() != null && m.getMemoryUsedKb() > 0)
                .toList();

        if (!metricsWithMemory.isEmpty()) {
            totalMemoryKb = metricsWithMemory.stream()
                    .mapToLong(PerformanceMetric::getMemoryUsedKb)
                    .sum();

            avgMemoryKb = totalMemoryKb / metricsWithMemory.size();

            minMemoryKb = metricsWithMemory.stream()
                    .mapToLong(PerformanceMetric::getMemoryUsedKb)
                    .min()
                    .orElse(0L);

            maxMemoryKb = metricsWithMemory.stream()
                    .mapToLong(PerformanceMetric::getMemoryUsedKb)
                    .max()
                    .orElse(0L);
        }

        LocalDateTime lastRequest = metricsList.stream()
                .map(PerformanceMetric::getTimestamp)
                .max(LocalDateTime::compareTo)
                .orElse(LocalDateTime.now());

        // Group by endpoint
        Map<String, EndpointStats> endpointStats = new HashMap<>();
        for (PerformanceMetric metric : metricsList) {
            String key = metric.getMethod() + " " + metric.getEndpoint();
            endpointStats.computeIfAbsent(
                    key,
                    k -> new EndpointStats(metric.getEndpoint(), metric.getMethod(), metric.getEntityName())
            ).addMetric(metric);
        }

        // Top 5 slowest endpoints
        Map<String, Long> topSlowEndpoints = endpointStats.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(e2.getValue().getMaxExecutionTimeMs(),
                        e1.getValue().getMaxExecutionTimeMs()))
                .limit(5)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().getMaxExecutionTimeMs(),
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));

        // Top 5 endpoints with most errors
        Map<String, Long> topErrorEndpoints = endpointStats.entrySet().stream()
                .filter(e -> e.getValue().getFailedCalls() > 0)
                .sorted((e1, e2) -> Long.compare(e2.getValue().getFailedCalls(),
                        e1.getValue().getFailedCalls()))
                .limit(5)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().getFailedCalls(),
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));

        // Top 5 memory-intensive endpoints
        Map<String, Long> topMemoryEndpoints = endpointStats.entrySet().stream()
                .filter(e -> e.getValue().getAvgMemoryKb() != null && e.getValue().getAvgMemoryKb() > 0)
                .sorted((e1, e2) -> Long.compare(e2.getValue().getMaxMemoryKb(),
                        e1.getValue().getMaxMemoryKb()))
                .limit(5)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().getMaxMemoryKb(),
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));

        return PerformanceSummary.builder()
                .totalRequests(total)
                .successfulRequests(successful)
                .failedRequests(failed)
                .successRate(successRate)
                .totalExecutionTimeMs(totalTime)
                .avgExecutionTimeMs(avgTime)
                .minExecutionTimeMs(minTime)
                .maxExecutionTimeMs(maxTime)
                .avgMemoryKb(avgMemoryKb)
                .minMemoryKb(minMemoryKb)
                .maxMemoryKb(maxMemoryKb)
                .totalMemoryKb(totalMemoryKb)
                .monitoringStartTime(startTime)
                .lastRequestTime(lastRequest)
                .endpointStats(endpointStats)
                .topSlowEndpoints(topSlowEndpoints)
                .topErrorEndpoints(topErrorEndpoints)
                .topMemoryEndpoints(topMemoryEndpoints)
                .build();
    }

    public void clearMetrics() {
        metrics.clear();
        log.info("Performance metrics cleared");
    }

    @Scheduled(fixedRate = 300000) // 5 minutes
    public void cleanupOldMetrics() {
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(properties.getRetentionMinutes());

        int removed = 0;
        while (!metrics.isEmpty()) {
            PerformanceMetric oldest = metrics.peekFirst();
            if (oldest != null && oldest.getTimestamp().isBefore(cutoffTime)) {
                metrics.removeFirst();
                removed++;
            } else {
                break;
            }
        }

        if (removed > 0) {
            log.debug("Cleaned up {} old performance metrics", removed);
        }
    }
}