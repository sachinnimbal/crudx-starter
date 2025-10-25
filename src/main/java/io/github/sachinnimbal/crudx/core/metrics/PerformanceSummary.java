package io.github.sachinnimbal.crudx.core.metrics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PerformanceSummary {
    private long totalRequests;
    private long successfulRequests;
    private long failedRequests;
    private double successRate;

    // Formatted time strings
    private String totalExecutionTime;
    private String avgExecutionTime;
    private String minExecutionTime;
    private String maxExecutionTime;

    // Formatted memory strings
    private String avgMemory;
    private String minMemory;
    private String maxMemory;
    private String totalMemory;

    // DTO conversion metrics
    private String totalDtoConversionTime;
    private String avgDtoConversionTime;
    private long totalDtoConversions;

    private LocalDateTime monitoringStartTime;
    private LocalDateTime lastRequestTime;
    private Map<String, EndpointStats> endpointStats;
    private Map<String, Long> topSlowEndpoints;
    private Map<String, Long> topErrorEndpoints;
    private Map<String, Long> topMemoryEndpoints;
}