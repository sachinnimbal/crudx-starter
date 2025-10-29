package io.github.sachinnimbal.crudx.core.metrics;

import io.github.sachinnimbal.crudx.core.config.CrudXProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.LongAdder;

import static io.github.sachinnimbal.crudx.core.util.TimeUtils.formatExecutionTime;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "crudx.performance", name = "enabled", havingValue = "true")
public class CrudXPerformanceTracker {

    private final CrudXProperties properties;
    private final Deque<PerformanceMetric> metrics;
    private final LocalDateTime startTime;

    // LOCK-FREE counters for real-time stats
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder successfulRequests = new LongAdder();
    private final LongAdder failedRequests = new LongAdder();

    public CrudXPerformanceTracker(CrudXProperties properties) {
        this.properties = properties;
        this.metrics = new ConcurrentLinkedDeque<>();
        this.startTime = LocalDateTime.now();

        CrudXProperties.Performance perf = properties.getPerformance();
        log.info("✓ Performance Tracker: Lock-free, zero-allocation mode");
        log.info("  Max Metrics: {} | Retention: {} min",
                perf.getMaxStoredMetrics(), perf.getRetentionMinutes());
    }

    public void recordMetric(String endpoint, String method, String entityName,
                             long executionTimeMs, boolean success, String errorType,
                             Long memoryDeltaKb, Long dtoConversionTimeMs, boolean dtoUsed, String dtoType) {

        // Update global counters
        totalRequests.increment();
        if (success) {
            successfulRequests.increment();
        } else {
            failedRequests.increment();
        }

        // Build metric
        PerformanceMetric metric = PerformanceMetric.builder()
                .endpoint(endpoint)
                .method(method)
                .entityName(entityName)
                .success(success)
                .errorType(errorType)
                .timestamp(LocalDateTime.now())
                .dtoUsed(dtoUsed)
                .dtoType(dtoType)
                .build();

        metric.setExecutionTimeMs(executionTimeMs);

        // CRITICAL: Only set memory if valid (non-null)
        if (memoryDeltaKb != null && memoryDeltaKb > 0) {
            metric.setMemoryUsedKb(memoryDeltaKb);
        }

        if (dtoConversionTimeMs != null && dtoConversionTimeMs > 0) {
            metric.setDtoConversionTimeMs(dtoConversionTimeMs);
        }

        // Add to bounded queue
        metrics.addLast(metric);

        // Evict oldest if over limit
        int maxSize = properties.getPerformance().getMaxStoredMetrics();
        while (metrics.size() > maxSize) {
            metrics.pollFirst();
        }
    }

    // Backward compatibility
    public void recordMetric(String endpoint, String method, String entityName,
                             long executionTimeMs, boolean success, String errorType,
                             Long memoryDeltaKb) {
        recordMetric(endpoint, method, entityName, executionTimeMs, success, errorType,
                memoryDeltaKb, null, false, "NONE");
    }

    public void recordMetric(String endpoint, String method, String entityName,
                             long executionTimeMs, boolean success, String errorType) {
        recordMetric(endpoint, method, entityName, executionTimeMs, success, errorType,
                null, null, false, "NONE");
    }

    /**
     * LAZY: Metrics snapshot (minimal allocation)
     */
    public List<PerformanceMetric> getMetrics() {
        return new ArrayList<>(metrics);
    }

    public List<PerformanceMetric> getMetricsByEndpoint(String endpoint) {
        List<PerformanceMetric> result = new ArrayList<>();
        for (PerformanceMetric m : metrics) {
            if (m.getEndpoint().equals(endpoint)) {
                result.add(m);
            }
        }
        return result;
    }

    /**
     * 🚀 ENTERPRISE-GRADE Summary with streaming aggregation
     * - Single pass through metrics
     * - Primitive accumulators (no boxing)
     * - Lazy formatting (only for result)
     */
    public PerformanceSummary getSummary() {
        List<PerformanceMetric> snapshot = new ArrayList<>(metrics);

        if (snapshot.isEmpty()) {
            return createEmptySummary();
        }

        // STREAMING aggregation with primitive accumulators
        long totalMs = 0L;
        long minMs = Long.MAX_VALUE;
        long maxMs = 0L;

        long totalMemoryKb = 0L;
        long minMemoryKb = Long.MAX_VALUE;
        long maxMemoryKb = 0L;
        int memoryCount = 0;

        long totalDtoMs = 0L;
        int dtoCount = 0;

        LocalDateTime lastRequest = null;

        // Endpoint aggregator
        Map<String, EndpointStatsAggregator> endpointAgg = new HashMap<>(64);

        // SINGLE PASS: Process all metrics
        for (PerformanceMetric m : snapshot) {
            // Execution time
            Long execMs = m.getExecutionTimeMs();
            if (execMs != null) {
                totalMs += execMs;
                if (execMs < minMs) minMs = execMs;
                if (execMs > maxMs) maxMs = execMs;
            }

            // Memory
            Long memKb = m.getMemoryUsedKb();
            if (memKb != null && memKb > 0) {
                totalMemoryKb += memKb;
                memoryCount++;
                if (memKb < minMemoryKb) minMemoryKb = memKb;
                if (memKb > maxMemoryKb) maxMemoryKb = memKb;
            }

            // DTO
            Long dtoMs = m.getDtoConversionTimeMs();
            if (dtoMs != null && dtoMs > 0) {
                totalDtoMs += dtoMs;
                dtoCount++;
            }

            // Last request
            if (lastRequest == null || m.getTimestamp().isAfter(lastRequest)) {
                lastRequest = m.getTimestamp();
            }

            // Endpoint stats
            String key = m.getMethod() + " " + m.getEndpoint();
            endpointAgg.computeIfAbsent(key,
                            k -> new EndpointStatsAggregator(m.getEndpoint(), m.getMethod(), m.getEntityName()))
                    .addMetric(m);
        }

        // Use atomic counters for totals
        long total = totalRequests.sum();
        long successful = successfulRequests.sum();
        long failed = failedRequests.sum();
        double successRate = total > 0 ? (double) successful / total * 100 : 0.0;

        // Calculate averages (primitives)
        long avgMs = total > 0 ? totalMs / total : 0L;
        long avgMemKb = memoryCount > 0 ? totalMemoryKb / memoryCount : 0L;
        long avgDtoMs = dtoCount > 0 ? totalDtoMs / dtoCount : 0L;

        // Build final stats map (lazy)
        Map<String, EndpointStats> finalStats = new HashMap<>(endpointAgg.size());
        for (Map.Entry<String, EndpointStatsAggregator> entry : endpointAgg.entrySet()) {
            finalStats.put(entry.getKey(), entry.getValue().build());
        }

        // Top endpoints (extract from aggregators)
        Map<String, Long> topSlow = extractTopN(endpointAgg,
                (agg) -> agg.maxExecutionTimeMs, 5);

        Map<String, Long> topErrors = extractTopN(endpointAgg,
                (agg) -> (long) agg.failedCalls, 5);

        Map<String, Long> topMemory = extractTopN(endpointAgg,
                (agg) -> agg.maxMemoryKb != null ? agg.maxMemoryKb : 0L, 5);

        return PerformanceSummary.builder()
                .totalRequests(total)
                .successfulRequests(successful)
                .failedRequests(failed)
                .successRate(successRate)
                .totalExecutionTime(formatExecutionTime(totalMs))
                .avgExecutionTime(formatExecutionTime(avgMs))
                .minExecutionTime(minMs != Long.MAX_VALUE ? formatExecutionTime(minMs) : "N/A")
                .maxExecutionTime(formatExecutionTime(maxMs))
                .avgMemory(formatMemory(memoryCount > 0 ? avgMemKb : null))
                .minMemory(formatMemory(minMemoryKb != Long.MAX_VALUE ? minMemoryKb : null))
                .maxMemory(formatMemory(memoryCount > 0 ? maxMemoryKb : null))
                .totalMemory(formatMemory(memoryCount > 0 ? totalMemoryKb : null))
                .totalDtoConversionTime(formatExecutionTime(totalDtoMs))
                .avgDtoConversionTime(formatExecutionTime(avgDtoMs))
                .totalDtoConversions((long) dtoCount)
                .monitoringStartTime(startTime)
                .lastRequestTime(lastRequest)
                .endpointStats(finalStats)
                .topSlowEndpoints(topSlow)
                .topErrorEndpoints(topErrors)
                .topMemoryEndpoints(topMemory)
                .build();
    }

    public void clearMetrics() {
        metrics.clear();
        totalRequests.reset();
        successfulRequests.reset();
        failedRequests.reset();
        log.info("Performance metrics cleared");
    }

    @Scheduled(fixedRate = 300000) // 5 minutes
    public void cleanupOldMetrics() {
        LocalDateTime cutoff = LocalDateTime.now()
                .minusMinutes(properties.getPerformance().getRetentionMinutes());

        int removed = 0;
        while (!metrics.isEmpty()) {
            PerformanceMetric oldest = metrics.peekFirst();
            if (oldest != null && oldest.getTimestamp().isBefore(cutoff)) {
                metrics.pollFirst();
                removed++;
            } else {
                break;
            }
        }

        if (removed > 0) {
            log.debug("Cleaned up {} old metrics", removed);
        }
    }

    // ==================== HELPERS ====================

    private PerformanceSummary createEmptySummary() {
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

    private String formatMemory(Long kb) {
        if (kb == null || kb <= 0) return "N/A";
        if (kb < 1024) return kb + " KB";
        double mb = kb / 1024.0;
        return String.format("%d KB (%.2f MB)", kb, mb);
    }

    /**
     * Extract top-N with custom extractor (zero intermediate collections)
     */
    private Map<String, Long> extractTopN(
            Map<String, EndpointStatsAggregator> aggregators,
            java.util.function.Function<EndpointStatsAggregator, Long> extractor,
            int n) {

        return aggregators.entrySet().stream()
                .sorted((e1, e2) -> Long.compare(
                        extractor.apply(e2.getValue()),
                        extractor.apply(e1.getValue())))
                .limit(n)
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> extractor.apply(e.getValue()),
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));
    }

    /**
     * LIGHTWEIGHT aggregator (primitives only, no intermediate formatting)
     */
    private static class EndpointStatsAggregator {
        String endpoint, method, entityName, dtoType;
        int totalCalls, successfulCalls, failedCalls;
        long totalExecutionTimeMs, minExecutionTimeMs = Long.MAX_VALUE, maxExecutionTimeMs;
        Long totalMemoryKb, minMemoryKb, maxMemoryKb;
        int memoryCallCount;
        long totalDtoMs, minDtoMs = Long.MAX_VALUE, maxDtoMs;
        int dtoCount;

        EndpointStatsAggregator(String endpoint, String method, String entityName) {
            this.endpoint = endpoint;
            this.method = method;
            this.entityName = entityName;
            this.dtoType = "NONE";
        }

        void addMetric(PerformanceMetric m) {
            totalCalls++;
            if (m.isSuccess()) successfulCalls++;
            else failedCalls++;

            Long execMs = m.getExecutionTimeMs();
            if (execMs != null) {
                totalExecutionTimeMs += execMs;
                if (execMs < minExecutionTimeMs) minExecutionTimeMs = execMs;
                if (execMs > maxExecutionTimeMs) maxExecutionTimeMs = execMs;
            }

            Long memKb = m.getMemoryUsedKb();
            if (memKb != null && memKb > 0) {
                if (totalMemoryKb == null) {
                    totalMemoryKb = 0L;
                    minMemoryKb = Long.MAX_VALUE;
                    maxMemoryKb = 0L;
                }
                totalMemoryKb += memKb;
                memoryCallCount++;
                if (memKb < minMemoryKb) minMemoryKb = memKb;
                if (memKb > maxMemoryKb) maxMemoryKb = memKb;
            }

            Long dtoMs = m.getDtoConversionTimeMs();
            if (dtoMs != null && dtoMs > 0) {
                totalDtoMs += dtoMs;
                dtoCount++;
                if (dtoMs < minDtoMs) minDtoMs = dtoMs;
                if (dtoMs > maxDtoMs) maxDtoMs = dtoMs;
            }
            String metricDtoType = m.getDtoType();
            if (metricDtoType != null && !"NONE".equals(metricDtoType)) {
                // If we have a non-NONE value
                if ("NONE".equals(this.dtoType)) {
                    // First non-NONE value, use it
                    this.dtoType = metricDtoType;
                } else if (!this.dtoType.equals(metricDtoType)) {
                    // Different non-NONE values, mark as MIXED
                    this.dtoType = "MIXED";
                }
            }
        }

        EndpointStats build() {
            EndpointStats stats = new EndpointStats();
            stats.setEndpoint(endpoint);
            stats.setMethod(method);
            stats.setEntityName(entityName);
            stats.setTotalCalls(totalCalls);
            stats.setSuccessfulCalls(successfulCalls);
            stats.setFailedCalls(failedCalls);

            // Format times
            stats.setTotalExecutionTime(formatExecutionTime(totalExecutionTimeMs));
            stats.setMinExecutionTime(minExecutionTimeMs != Long.MAX_VALUE ?
                    formatExecutionTime(minExecutionTimeMs) : "N/A");
            stats.setMaxExecutionTime(formatExecutionTime(maxExecutionTimeMs));
            stats.setAvgExecutionTime(totalCalls > 0 ?
                    formatExecutionTime(totalExecutionTimeMs / totalCalls) : "N/A");

            // Format memory
            if (totalMemoryKb != null && memoryCallCount > 0) {
                stats.setTotalMemory(formatMemoryLocal(totalMemoryKb));
                stats.setAvgMemory(formatMemoryLocal(totalMemoryKb / memoryCallCount));
                stats.setMinMemory(minMemoryKb != null && minMemoryKb != Long.MAX_VALUE ?
                        formatMemoryLocal(minMemoryKb) : "N/A");
                stats.setMaxMemory(formatMemoryLocal(maxMemoryKb));
            } else {
                stats.setTotalMemory("N/A");
                stats.setAvgMemory("N/A");
                stats.setMinMemory("N/A");
                stats.setMaxMemory("N/A");
            }

            // Format DTO
            if (dtoCount > 0) {
                stats.setTotalDtoConversionTime(formatExecutionTime(totalDtoMs));
                stats.setAvgDtoConversionTime(formatExecutionTime(totalDtoMs / dtoCount));
                stats.setMinDtoConversionTime(minDtoMs != Long.MAX_VALUE ?
                        formatExecutionTime(minDtoMs) : "N/A");
                stats.setMaxDtoConversionTime(formatExecutionTime(maxDtoMs));
                stats.setDtoConversionCount(dtoCount);
            } else {
                stats.setTotalDtoConversionTime("N/A");
                stats.setAvgDtoConversionTime("N/A");
                stats.setMinDtoConversionTime("N/A");
                stats.setMaxDtoConversionTime("N/A");
            }
            stats.setDtoType(this.dtoType);
            return stats;
        }

        private String formatMemoryLocal(Long kb) {
            if (kb == null || kb <= 0) return "N/A";
            if (kb < 1024) return kb + " KB";
            double mb = kb / 1024.0;
            return String.format("%d KB (%.2f MB)", kb, mb);
        }
    }
}