package io.github.sachinnimbal.crudx.core.performance;


import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static io.github.sachinnimbal.crudx.core.util.TimeUtils.formatExecutionTime;

/**
 * 🔥 ULTRA-LIGHTWEIGHT Batch Metrics Tracker
 * Tracks per-object timing with reservoir sampling (max 1000 samples)
 * Memory-optimized: ~30-50MB for 100K records
 */
@Slf4j
public class CrudXBatchMetricsTracker {

    private final long startTime;
    private final long memoryBeforeBytes;

    private long dtoMappingTimeMs = 0;
    private long validationTimeMs = 0;
    private long dbWriteTimeMs = 0;

    /**
     * -- GETTER --
     * Get current success count
     */
    @Getter
    private int successCount = 0;
    /**
     * -- GETTER --
     * Get current failed count
     */
    @Getter
    private int failedCount = 0;

    // 🔥 CRITICAL: Reservoir sampling for object timings
    private final List<ObjectTiming> sampleTimings = new ArrayList<>(1000);
    private final Random random = new Random();
    private int processedCount = 0;

    private static final int MAX_SAMPLES = 1000;

    public CrudXBatchMetricsTracker() {
        this.startTime = System.currentTimeMillis();
        Runtime runtime = Runtime.getRuntime();
        this.memoryBeforeBytes = runtime.totalMemory() - runtime.freeMemory();
    }

    /**
     * Track DTO mapping time (aggregate)
     */
    public void addDtoMappingTime(long millis) {
        this.dtoMappingTimeMs += millis;
    }

    /**
     * Track validation time (aggregate)
     */
    public void addValidationTime(long millis) {
        this.validationTimeMs += millis;
    }

    /**
     * Track DB write time (aggregate)
     */
    public void addDbWriteTime(long millis) {
        this.dbWriteTimeMs += millis;
    }

    /**
     * Increment success count
     */
    public void incrementSuccess() {
        this.successCount++;
    }

    /**
     * Increment failed count
     */
    public void incrementFailed() {
        this.failedCount++;
    }

    /**
     * 🔥 CRITICAL: Add object timing with reservoir sampling
     * Keeps only 1000 random samples to prevent memory bloat
     */
    public void addObjectTiming(String id, long mappingMs, long validationMs, long writeMs) {
        processedCount++;

        long totalMs = mappingMs + validationMs + writeMs;

        ObjectTiming timing = ObjectTiming.builder()
                .id(id)
                .mappingTime(formatExecutionTime(mappingMs))
                .validationTime(formatExecutionTime(validationMs))
                .writeTime(formatExecutionTime(writeMs))
                .totalTime(formatExecutionTime(totalMs))
                .build();

        if (sampleTimings.size() < MAX_SAMPLES) {
            // Still under limit, add directly
            sampleTimings.add(timing);
        } else {
            // Reservoir sampling: Random replacement
            int randomIndex = random.nextInt(MAX_SAMPLES);
            sampleTimings.set(randomIndex, timing);
        }
    }

    /**
     * Build final EndpointMetrics
     */
    public EndpointMetrics buildMetrics(String endpoint, String method) {
        long endTime = System.currentTimeMillis();
        long executionTimeMs = endTime - startTime;

        Runtime runtime = Runtime.getRuntime();
        long memoryAfterBytes = runtime.totalMemory() - runtime.freeMemory();
        long memoryPeakBytes = runtime.totalMemory();

        double memoryBeforeMB = memoryBeforeBytes / 1024.0 / 1024.0;
        double memoryAfterMB = memoryAfterBytes / 1024.0 / 1024.0;
        double memoryPeakMB = memoryPeakBytes / 1024.0 / 1024.0;

        EndpointMetrics metrics = EndpointMetrics.builder()
                .endpoint(endpoint)
                .method(method)
                .totalRecords(processedCount)
                .successCount(successCount)
                .failedCount(failedCount)
                .avgExecutionTimeMs(executionTimeMs)
                .memoryBeforeMB(memoryBeforeMB)
                .memoryPeakMB(memoryPeakMB)
                .memoryAfterMB(memoryAfterMB)
                .dtoMappingTime(dtoMappingTimeMs > 0 ? formatExecutionTime(dtoMappingTimeMs) : null)
                .validationTime(validationTimeMs > 0 ? formatExecutionTime(validationTimeMs) : null)
                .dbWriteTime(dbWriteTimeMs > 0 ? formatExecutionTime(dbWriteTimeMs) : null)
                .sampleObjectTimings(new ArrayList<>(sampleTimings))
                .lastInvocationTime(endTime)
                .build();

        metrics.calculateThroughput();

        return metrics;
    }

    /**
     * Get execution time so far
     */
    public long getExecutionTimeMs() {
        return System.currentTimeMillis() - startTime;
    }
}