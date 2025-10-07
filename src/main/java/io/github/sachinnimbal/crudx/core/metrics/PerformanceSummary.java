/*
 * Copyright 2025 Sachin Nimbal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.sachinnimbal.crudx.core.metrics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * @author Sachin Nimbal
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
