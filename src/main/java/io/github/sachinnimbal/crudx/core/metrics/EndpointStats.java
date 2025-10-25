package io.github.sachinnimbal.crudx.core.metrics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EndpointStats {
    private String endpoint;
    private String method;
    private String entityName;
    private long totalCalls;
    private long successfulCalls;
    private long failedCalls;
    private long totalExecutionTimeMs;
    private long minExecutionTimeMs;
    private long maxExecutionTimeMs;
    private double avgExecutionTimeMs;

    // Memory tracking
    private Long totalMemoryKb;
    private Long avgMemoryKb;
    private Long minMemoryKb;
    private Long maxMemoryKb;

    private LocalDateTime firstCall;
    private LocalDateTime lastCall;
    private Map<String, Long> errorCounts;
    private long memoryCallCount;

    public EndpointStats(String endpoint, String method, String entityName) {
        this.endpoint = endpoint;
        this.method = method;
        this.entityName = entityName;
        this.totalCalls = 0;
        this.successfulCalls = 0;
        this.failedCalls = 0;
        this.totalExecutionTimeMs = 0;
        this.minExecutionTimeMs = Long.MAX_VALUE;
        this.maxExecutionTimeMs = 0;
        this.totalMemoryKb = 0L;
        this.minMemoryKb = Long.MAX_VALUE;
        this.maxMemoryKb = 0L;
        this.errorCounts = new HashMap<>();
    }

    public void addMetric(PerformanceMetric metric) {
        totalCalls++;
        if (!metric.isSuccess()) {
            failedCalls++;
        }

        totalExecutionTimeMs += metric.getExecutionTimeMs();
        avgExecutionTimeMs = (double) totalExecutionTimeMs / totalCalls;

        if (metric.getExecutionTimeMs() < minExecutionTimeMs) {
            minExecutionTimeMs = metric.getExecutionTimeMs();
        }
        if (metric.getExecutionTimeMs() > maxExecutionTimeMs) {
            maxExecutionTimeMs = metric.getExecutionTimeMs();
        }

        Long memoryKb = metric.getMemoryUsedKb();
        if (memoryKb != null && memoryKb > 0) {
            if (totalMemoryKb == null) {
                totalMemoryKb = 0L;
            }
            totalMemoryKb += memoryKb;
            memoryCallCount++;

            // Calculate average only from metrics with memory data
            avgMemoryKb = totalMemoryKb / memoryCallCount;

            // Initialize min/max on first valid memory value
            if (minMemoryKb == null || memoryKb < minMemoryKb) {
                minMemoryKb = memoryKb;
            }
            if (maxMemoryKb == null || memoryKb > maxMemoryKb) {
                maxMemoryKb = memoryKb;
            }
        }
    }

    // Formatted memory getters
    public String getAvgMemoryFormatted() {
        return formatMemory(avgMemoryKb);
    }

    public String getMaxMemoryFormatted() {
        return formatMemory(maxMemoryKb);
    }

    private String formatMemory(Long kb) {
        if (kb == null) return null;

        if (kb < 1024) {
            return kb + " KB";
        } else {
            double mb = kb / 1024.0;
            return String.format("%d KB (%.2f MB)", kb, mb);
        }
    }
}
