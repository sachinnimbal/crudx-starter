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

import static io.github.sachinnimbal.crudx.core.util.TimeUtils.formatExecutionTime;

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
                             Long memoryDeltaKb, Long dtoConversionTimeMs, boolean dtoUsed) {

        PerformanceMetric metric = PerformanceMetric.builder()
                .endpoint(endpoint)
                .method(method)
                .entityName(entityName)
                .success(success)
                .errorType(errorType)
                .timestamp(LocalDateTime.now())
                .dtoUsed(dtoUsed)
                .build();

        // Use setters to trigger formatting
        metric.setExecutionTimeMs(executionTimeMs);

        if (memoryDeltaKb != null && memoryDeltaKb > 0) {
            metric.setMemoryUsedKb(memoryDeltaKb);
        }

        if (dtoConversionTimeMs != null && dtoConversionTimeMs > 0) {
            metric.setDtoConversionTimeMs(dtoConversionTimeMs);
        }

        metrics.addLast(metric);

        while (metrics.size() > properties.getMaxStoredMetrics()) {
            metrics.removeFirst();
        }
    }

    // Backward compatibility
    public void recordMetric(String endpoint, String method, String entityName,
                             long executionTimeMs, boolean success, String errorType,
                             Long memoryDeltaKb) {
        recordMetric(endpoint, method, entityName, executionTimeMs, success, errorType,
                memoryDeltaKb, null, false);
    }

    // Backward compatibility
    public void recordMetric(String endpoint, String method, String entityName,
                             long executionTimeMs, boolean success, String errorType) {
        recordMetric(endpoint, method, entityName, executionTimeMs, success, errorType,
                null, null, false);
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
                    .successRate(0.0)
                    .totalExecutionTime("0ms")
                    .avgExecutionTime("0ms")
                    .minExecutionTime("0ms")
                    .maxExecutionTime("0ms")
                    .avgMemory("N/A")
                    .minMemory("N/A")
                    .maxMemory("N/A")
                    .totalMemory("N/A")
                    .totalDtoConversionTime("0ms")
                    .avgDtoConversionTime("N/A")
                    .totalDtoConversions(0L)
                    .monitoringStartTime(startTime)
                    .build();
        }

        long total = metricsList.size();
        long successful = metricsList.stream().filter(PerformanceMetric::isSuccess).count();
        long failed = total - successful;
        double successRate = (double) successful / total * 100;

        // Calculate execution time totals
        long totalTimeMs = metricsList.stream()
                .map(PerformanceMetric::getExecutionTimeMs)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();

        long minTimeMs = metricsList.stream()
                .map(PerformanceMetric::getExecutionTimeMs)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .min()
                .orElse(0);

        long maxTimeMs = metricsList.stream()
                .map(PerformanceMetric::getExecutionTimeMs)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .max()
                .orElse(0);

        double avgTimeMs = (double) totalTimeMs / total;

        // Memory calculations
        List<PerformanceMetric> metricsWithMemory = metricsList.stream()
                .filter(m -> m.getMemoryUsedKb() != null && m.getMemoryUsedKb() > 0)
                .toList();

        Long totalMemoryKb = null;
        Long avgMemoryKb = null;
        Long minMemoryKb = null;
        Long maxMemoryKb = null;

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

        // DTO conversion calculations - FIXED
        List<PerformanceMetric> metricsWithDto = metricsList.stream()
                .filter(m -> m.getDtoConversionTimeMs() != null && m.getDtoConversionTimeMs() > 0)
                .toList();

        long totalDtoMs = 0L;  // Changed from Long to primitive long, initialized to 0
        long avgDtoMs = 0L;    // Changed from Long to primitive long, initialized to 0

        if (!metricsWithDto.isEmpty()) {
            totalDtoMs = metricsWithDto.stream()
                    .mapToLong(PerformanceMetric::getDtoConversionTimeMs)
                    .sum();

            avgDtoMs = totalDtoMs / metricsWithDto.size();
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

        // Top 5 slowest endpoints (by raw ms values for sorting)
        Map<String, Long> topSlowEndpoints = endpointStats.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(
                        e2.getValue().getMaxExecutionTimeMs(),
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
                .sorted((e1, e2) -> Long.compare(
                        e2.getValue().getFailedCalls(),
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
                .filter(e -> e.getValue().getMaxMemoryKb() != null && e.getValue().getMaxMemoryKb() > 0)
                .sorted((e1, e2) -> {
                    Long mem1 = e1.getValue().getMaxMemoryKb();
                    Long mem2 = e2.getValue().getMaxMemoryKb();
                    if (mem1 == null) return 1;
                    if (mem2 == null) return -1;
                    return Long.compare(mem2, mem1);
                })
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
                .totalExecutionTime(formatExecutionTime(totalTimeMs))
                .avgExecutionTime(formatExecutionTime((long) avgTimeMs))
                .minExecutionTime(formatExecutionTime(minTimeMs))
                .maxExecutionTime(formatExecutionTime(maxTimeMs))
                .avgMemory(formatMemory(avgMemoryKb))
                .minMemory(formatMemory(minMemoryKb))
                .maxMemory(formatMemory(maxMemoryKb))
                .totalMemory(formatMemory(totalMemoryKb))
                .totalDtoConversionTime(formatExecutionTime(totalDtoMs))  // Now passes primitive long, no NPE
                .avgDtoConversionTime(formatExecutionTime(avgDtoMs))      // Now passes primitive long, no NPE
                .totalDtoConversions((long) metricsWithDto.size())
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

    // Helper method to format memory
    private String formatMemory(Long kb) {
        if (kb == null || kb <= 0) return "N/A";

        if (kb < 1024) {
            return kb + " KB";
        } else {
            double mb = kb / 1024.0;
            return String.format("%d KB (%.2f MB)", kb, mb);
        }
    }
}