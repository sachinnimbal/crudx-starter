package io.github.sachinnimbal.crudx.core.metrics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;


/**
 * @author Sachin Nimbal
 * @version 1.0.0
 * @since 2025
 * @Contact: <a href="mailto:sachinnimbal9@gmail.com">sachinnimbal9@gmail.com</a>
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
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

        if (metric.isSuccess()) {
            successfulCalls++;
        } else {
            failedCalls++;
            String errorType = metric.getErrorType() != null ? metric.getErrorType() : "Unknown";
            errorCounts.merge(errorType, 1L, Long::sum);
        }

        // Time tracking
        totalExecutionTimeMs += metric.getExecutionTimeMs();
        minExecutionTimeMs = Math.min(minExecutionTimeMs, metric.getExecutionTimeMs());
        maxExecutionTimeMs = Math.max(maxExecutionTimeMs, metric.getExecutionTimeMs());
        avgExecutionTimeMs = (double) totalExecutionTimeMs / totalCalls;

        // Memory tracking
        if (metric.getMemoryUsedKb() != null) {
            totalMemoryKb += metric.getMemoryUsedKb();
            minMemoryKb = Math.min(minMemoryKb, metric.getMemoryUsedKb());
            maxMemoryKb = Math.max(maxMemoryKb, metric.getMemoryUsedKb());
            avgMemoryKb = totalMemoryKb / totalCalls;
        }

        if (firstCall == null) {
            firstCall = metric.getTimestamp();
        }
        lastCall = metric.getTimestamp();
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
