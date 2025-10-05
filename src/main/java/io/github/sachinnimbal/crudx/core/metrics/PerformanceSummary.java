package io.github.sachinnimbal.crudx.core.metrics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
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
public class PerformanceSummary {
    private long totalRequests;
    private long successfulRequests;
    private long failedRequests;
    private double successRate;
    private long totalExecutionTimeMs;
    private double avgExecutionTimeMs;
    private long minExecutionTimeMs;
    private long maxExecutionTimeMs;

    // Memory metrics
    private Long avgMemoryKb;
    private Long minMemoryKb;
    private Long maxMemoryKb;
    private Long totalMemoryKb;

    private LocalDateTime monitoringStartTime;
    private LocalDateTime lastRequestTime;
    private Map<String, EndpointStats> endpointStats;
    private Map<String, Long> topSlowEndpoints;
    private Map<String, Long> topErrorEndpoints;
    private Map<String, Long> topMemoryEndpoints;
}