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

import com.fasterxml.jackson.annotation.JsonIgnore;
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
public class PerformanceMetric {
    private String endpoint;
    private String method;
    private String entityName;
    private long executionTimeMs;
    private LocalDateTime timestamp;
    private boolean success;
    private String errorType;

    // Memory tracking - store raw KB value
    private Long memoryUsedKb;

    // Computed property - returns formatted string with both KB and MB
    private String memoryUsedFormatted;

    private Map<String, Object> additionalData;

    // Helper method to format memory
    @JsonIgnore
    public String getFormattedMemory() {
        if (memoryUsedKb == null) return null;

        double mb = memoryUsedKb / 1024.0;

        if (memoryUsedKb < 1024) {
            // Less than 1 MB - show only KB
            return String.format("%d KB", memoryUsedKb);
        } else {
            // 1 MB or more - show both
            return String.format("%d KB (%.2f MB)", memoryUsedKb, mb);
        }
    }

    // Computed MB value
    public Double getMemoryUsedMb() {
        return memoryUsedKb != null ? memoryUsedKb / 1024.0 : null;
    }
}
