package io.github.sachinnimbal.crudx.core.performance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static io.github.sachinnimbal.crudx.core.util.TimeUtils.formatExecutionTime;

/**
 * 🔥 ULTRA-LIGHTWEIGHT Metrics Registry
 * Memory-optimized central storage for all endpoint performance metrics
 * Uses reservoir sampling to keep only last 1000 object timings per endpoint
 */
@Slf4j
@Component
public class CrudXMetricsRegistry {

    // Endpoint path -> Metrics
    private final Map<String, EndpointMetrics> endpointMetrics = new ConcurrentHashMap<>(64);

    // Global counters
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong totalSuccessfulRequests = new AtomicLong(0);
    private final AtomicLong totalFailedRequests = new AtomicLong(0);

    private static final int MAX_SAMPLE_TIMINGS = 1000; // Reservoir sampling limit
    private static final int MAX_ENDPOINTS = 100; // Prevent unbounded growth

    /**
     * Record metrics for an endpoint invocation
     */
    public void recordMetrics(String endpoint, EndpointMetrics metrics) {
        if (endpoint == null || metrics == null) return;

        totalRequests.incrementAndGet();

        if (metrics.getFailedCount() > 0) {
            totalFailedRequests.addAndGet(metrics.getFailedCount());
        }

        if (metrics.getSuccessCount() > 0) {
            totalSuccessfulRequests.addAndGet(metrics.getSuccessCount());
        }

        // 🔥 Merge with existing metrics or create new
        endpointMetrics.compute(endpoint, (key, existing) -> {
            if (existing == null) {
                // First invocation
                metrics.setInvocationCount(1L);
                return metrics;
            } else {
                // Merge with existing
                return mergeMetrics(existing, metrics);
            }
        });

        // 🔥 Memory safety: Limit endpoint tracking
        if (endpointMetrics.size() > MAX_ENDPOINTS) {
            log.warn("⚠️  Endpoint metrics limit reached ({}), clearing oldest entries", MAX_ENDPOINTS);
            clearOldestEntries();
        }
    }

    /**
     * Merge new metrics with existing metrics
     */
    private EndpointMetrics mergeMetrics(EndpointMetrics existing, EndpointMetrics newMetrics) {
        existing.setInvocationCount(existing.getInvocationCount() + 1);
        existing.setTotalRecords(existing.getTotalRecords() + newMetrics.getTotalRecords());
        existing.setSuccessCount(existing.getSuccessCount() + newMetrics.getSuccessCount());
        existing.setFailedCount(existing.getFailedCount() + newMetrics.getFailedCount());

        // Average timings
        long totalInvocations = existing.getInvocationCount();
        existing.setAvgExecutionTimeMs(
                (existing.getAvgExecutionTimeMs() * (totalInvocations - 1) + newMetrics.getAvgExecutionTimeMs()) / totalInvocations
        );

        // Peak memory tracking
        existing.setMemoryPeakMB(Math.max(existing.getMemoryPeakMB(), newMetrics.getMemoryPeakMB()));

        // 🔥 CRITICAL: Reservoir sampling for object timings
        mergeObjectTimings(existing, newMetrics.getSampleObjectTimings());

        return existing;
    }

    /**
     * 🔥 OPTIMIZATION: Reservoir sampling to keep only 1000 samples
     */
    private void mergeObjectTimings(EndpointMetrics existing, List<ObjectTiming> newTimings) {
        if (newTimings == null || newTimings.isEmpty()) return;

        List<ObjectTiming> existingTimings = existing.getSampleObjectTimings();

        for (ObjectTiming timing : newTimings) {
            if (existingTimings.size() < MAX_SAMPLE_TIMINGS) {
                // Still under limit, add directly
                existingTimings.add(timing);
            } else {
                // Reservoir sampling: Random replacement
                int randomIndex = new Random().nextInt(MAX_SAMPLE_TIMINGS);
                existingTimings.set(randomIndex, timing);
            }
        }
    }

    /**
     * Get metrics for a specific endpoint
     */
    public Optional<EndpointMetrics> getMetrics(String endpoint) {
        return Optional.ofNullable(endpointMetrics.get(endpoint));
    }

    /**
     * Get all endpoint metrics
     */
    public Map<String, EndpointMetrics> getAllMetrics() {
        return Collections.unmodifiableMap(endpointMetrics);
    }

    /**
     * Get global summary
     */
    public Map<String, Object> getGlobalSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();

        long totalReqs = totalRequests.get();
        long successReqs = totalSuccessfulRequests.get();
        long failedReqs = totalFailedRequests.get();

        summary.put("totalRequests", totalReqs);
        summary.put("successfulRequests", successReqs);
        summary.put("failedRequests", failedReqs);
        summary.put("successRate", totalReqs > 0
                ? String.format("%.2f%%", (successReqs * 100.0 / totalReqs))
                : "0.00%");
        summary.put("totalEndpoints", endpointMetrics.size());
        summary.put("memoryUsageMB", getCurrentMemoryUsage());

        return summary;
    }

    /**
     * Clear all metrics (for testing/reset)
     */
    public void clearAll() {
        endpointMetrics.clear();
        totalRequests.set(0);
        totalSuccessfulRequests.set(0);
        totalFailedRequests.set(0);
        log.info("✓ All metrics cleared");
    }

    /**
     * 🔥 MEMORY SAFETY: Clear oldest entries when limit reached
     */
    private void clearOldestEntries() {
        // Keep only 80% of entries
        int targetSize = (int) (MAX_ENDPOINTS * 0.8);

        List<Map.Entry<String, EndpointMetrics>> entries = new ArrayList<>(endpointMetrics.entrySet());
        entries.sort(Comparator.comparingLong(e -> e.getValue().getLastInvocationTime()));

        int toRemove = entries.size() - targetSize;
        for (int i = 0; i < toRemove; i++) {
            endpointMetrics.remove(entries.get(i).getKey());
        }

        log.info("✓ Cleared {} oldest endpoint entries", toRemove);
    }

    /**
     * Get current memory usage in MB
     */
    private String getCurrentMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
        return usedMemory + " MB";
    }

    /**
     * Get full performance report
     */
    public Map<String, Object> getFullPerformanceReport() {
        Map<String, Object> report = new LinkedHashMap<>();

        // Global summary
        report.put("globalSummary", getGlobalSummary());

        // Per-endpoint metrics (formatted)
        Map<String, Map<String, Object>> endpointReports = new LinkedHashMap<>();

        endpointMetrics.forEach((endpoint, metrics) -> {
            Map<String, Object> endpointReport = new LinkedHashMap<>();

            endpointReport.put("invocationCount", metrics.getInvocationCount());
            endpointReport.put("totalRecords", metrics.getTotalRecords());
            endpointReport.put("successCount", metrics.getSuccessCount());
            endpointReport.put("failedCount", metrics.getFailedCount());

            // 🔥 CRITICAL: Format all times using TimeUtils
            endpointReport.put("avgExecutionTime", formatExecutionTime(metrics.getAvgExecutionTimeMs()));
            endpointReport.put("throughput", metrics.getThroughput());
            endpointReport.put("memoryBeforeMB", metrics.getMemoryBeforeMB() + " MB");
            endpointReport.put("memoryPeakMB", metrics.getMemoryPeakMB() + " MB");
            endpointReport.put("memoryAfterMB", metrics.getMemoryAfterMB() + " MB");

            if (metrics.getDtoMappingTime() != null) {
                endpointReport.put("dtoMappingTime", metrics.getDtoMappingTime());
            }
            if (metrics.getValidationTime() != null) {
                endpointReport.put("validationTime", metrics.getValidationTime());
            }
            if (metrics.getDbWriteTime() != null) {
                endpointReport.put("dbWriteTime", metrics.getDbWriteTime());
            }

            // Sample timings (max 10 for display)
            if (!metrics.getSampleObjectTimings().isEmpty()) {
                List<ObjectTiming> samples = metrics.getSampleObjectTimings()
                        .subList(0, Math.min(10, metrics.getSampleObjectTimings().size()));
                endpointReport.put("sampleObjectTimings", samples);
            }

            endpointReport.put("lastInvocationTime",
                    new Date(metrics.getLastInvocationTime()).toString());

            endpointReports.put(endpoint, endpointReport);
        });

        report.put("endpointMetrics", endpointReports);

        return report;
    }
}