package io.github.sachinnimbal.crudx.core.performance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 🔥 ULTRA-LIGHTWEIGHT Endpoint Metrics Model
 * All time fields are String (formatted) for response compatibility
 * Internal tracking uses milliseconds, but output is ALWAYS String
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EndpointMetrics {

    // Endpoint identification
    private String endpoint;
    private String method; // GET, POST, etc.

    // Invocation tracking
    private long invocationCount;
    private long lastInvocationTime; // System.currentTimeMillis()

    // Record counts
    private long totalRecords;
    private long successCount;
    private long failedCount;

    // Timing (internal: millis, output: String)
    private long avgExecutionTimeMs; // Internal tracking
    private String throughput; // Already formatted: "28500 rec/sec"

    // Memory tracking (MB)
    private double memoryBeforeMB;
    private double memoryPeakMB;
    private double memoryAfterMB;

    // Breakdown timings (String only in output)
    private String dtoMappingTime;
    private String validationTime;
    private String dbWriteTime;

    // 🔥 CRITICAL: Reservoir sampling - max 1000 samples
    @Builder.Default
    private List<ObjectTiming> sampleObjectTimings = new ArrayList<>();

    /**
     * Calculate and format throughput
     */
    public void calculateThroughput() {
        if (avgExecutionTimeMs > 0 && successCount > 0) {
            double recordsPerSecond = (successCount * 1000.0) / avgExecutionTimeMs;
            this.throughput = String.format("%.0f rec/sec", recordsPerSecond);
        } else {
            this.throughput = "N/A";
        }
    }
}