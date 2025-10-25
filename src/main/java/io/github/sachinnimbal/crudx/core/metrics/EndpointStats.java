package io.github.sachinnimbal.crudx.core.metrics;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static io.github.sachinnimbal.crudx.core.util.TimeUtils.formatExecutionTime;

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

    // Formatted time strings
    private String totalExecutionTime;
    private String minExecutionTime;
    private String maxExecutionTime;
    private String avgExecutionTime;

    // Formatted memory strings
    private String totalMemory;
    private String avgMemory;
    private String minMemory;
    private String maxMemory;

    // DTO conversion metrics (formatted)
    private String totalDtoConversionTime;
    private String avgDtoConversionTime;
    private String minDtoConversionTime;
    private String maxDtoConversionTime;
    private long dtoConversionCount;

    private LocalDateTime firstCall;
    private LocalDateTime lastCall;
    private Map<String, Long> errorCounts;

    // Internal storage (not serialized)
    @JsonIgnore
    private long totalExecutionTimeMs;
    @JsonIgnore
    private long minExecutionTimeMs;
    @JsonIgnore
    private long maxExecutionTimeMs;
    @JsonIgnore
    private Long totalMemoryKb;
    @JsonIgnore
    private Long minMemoryKb;
    @JsonIgnore
    private Long maxMemoryKb;
    @JsonIgnore
    private long memoryCallCount;
    @JsonIgnore
    private Long totalDtoConversionTimeMs;
    @JsonIgnore
    private Long minDtoConversionTimeMs;
    @JsonIgnore
    private Long maxDtoConversionTimeMs;

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
        this.totalDtoConversionTimeMs = 0L;
        this.minDtoConversionTimeMs = Long.MAX_VALUE;
        this.maxDtoConversionTimeMs = 0L;
        this.dtoConversionCount = 0;
        this.errorCounts = new HashMap<>();
    }

    public void addMetric(PerformanceMetric metric) {
        totalCalls++;
        if (metric.isSuccess()) {
            successfulCalls++;
        } else {
            failedCalls++;
        }

        // Execution time
        Long execMs = metric.getExecutionTimeMs();
        if (execMs != null) {
            totalExecutionTimeMs += execMs;
            if (execMs < minExecutionTimeMs) minExecutionTimeMs = execMs;
            if (execMs > maxExecutionTimeMs) maxExecutionTimeMs = execMs;
        }

        // Memory
        Long memoryKb = metric.getMemoryUsedKb();
        if (memoryKb != null && memoryKb > 0) {
            totalMemoryKb += memoryKb;
            memoryCallCount++;
            if (memoryKb < minMemoryKb) minMemoryKb = memoryKb;
            if (memoryKb > maxMemoryKb) maxMemoryKb = memoryKb;
        }

        // DTO conversion
        Long dtoMs = metric.getDtoConversionTimeMs();
        if (dtoMs != null && dtoMs > 0) {
            totalDtoConversionTimeMs += dtoMs;
            dtoConversionCount++;
            if (dtoMs < minDtoConversionTimeMs) minDtoConversionTimeMs = dtoMs;
            if (dtoMs > maxDtoConversionTimeMs) maxDtoConversionTimeMs = dtoMs;
        }

        // Update formatted strings
        updateFormattedValues();
    }

    private void updateFormattedValues() {
        // Execution times
        totalExecutionTime = formatExecutionTime(totalExecutionTimeMs);
        minExecutionTime = minExecutionTimeMs != Long.MAX_VALUE ? formatExecutionTime(minExecutionTimeMs) : "N/A";
        maxExecutionTime = formatExecutionTime(maxExecutionTimeMs);
        avgExecutionTime = totalCalls > 0 ? formatExecutionTime(totalExecutionTimeMs / totalCalls) : "N/A";

        // Memory
        totalMemory = formatMemory(totalMemoryKb);
        avgMemory = memoryCallCount > 0 ? formatMemory(totalMemoryKb / memoryCallCount) : "N/A";
        minMemory = (minMemoryKb != null && minMemoryKb != Long.MAX_VALUE) ? formatMemory(minMemoryKb) : "N/A";
        maxMemory = formatMemory(maxMemoryKb);

        // DTO conversion - FIX: Handle cases where no DTO conversions occurred
        if (dtoConversionCount > 0) {
            totalDtoConversionTime = formatExecutionTime(totalDtoConversionTimeMs);
            avgDtoConversionTime = formatExecutionTime(totalDtoConversionTimeMs / dtoConversionCount);
            minDtoConversionTime = (minDtoConversionTimeMs != null && minDtoConversionTimeMs != Long.MAX_VALUE)
                    ? formatExecutionTime(minDtoConversionTimeMs) : "N/A";
            maxDtoConversionTime = formatExecutionTime(maxDtoConversionTimeMs);
        } else {
            // No DTO conversions - set all to N/A
            totalDtoConversionTime = "N/A";
            avgDtoConversionTime = "N/A";
            minDtoConversionTime = "N/A";
            maxDtoConversionTime = "N/A";
        }
    }

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