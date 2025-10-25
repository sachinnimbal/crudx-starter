package io.github.sachinnimbal.crudx.core.metrics;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
public class PerformanceMetric {
    private String endpoint;
    private String method;
    private String entityName;
    private String executionTime; // Changed to String with format
    private LocalDateTime timestamp;
    private boolean success;
    private String errorType;

    // Memory tracking - formatted strings
    private String memoryUsed;

    // DTO conversion metrics
    private String dtoConversionTime;
    private boolean dtoUsed;

    private Map<String, Object> additionalData;

    // Internal storage (not serialized)
    @JsonIgnore
    private Long executionTimeMs;

    @JsonIgnore
    private Long memoryUsedKb;

    @JsonIgnore
    private Long dtoConversionTimeMs;

    // Format execution time on creation
    public void setExecutionTimeMs(Long ms) {
        this.executionTimeMs = ms;
        this.executionTime = formatTime(ms);
    }

    public void setMemoryUsedKb(Long kb) {
        this.memoryUsedKb = kb;
        this.memoryUsed = formatMemory(kb);
    }

    public void setDtoConversionTimeMs(Long ms) {
        this.dtoConversionTimeMs = ms;
        this.dtoConversionTime = formatTime(ms);
    }

    private String formatTime(Long ms) {
        if (ms == null) return null;
        if (ms < 1000) {
            return ms + "ms";
        } else if (ms < 60000) {
            double seconds = ms / 1000.0;
            return String.format("%dms (%.2fs)", ms, seconds);
        } else {
            long minutes = ms / 60000;
            long seconds = (ms % 60000) / 1000;
            return String.format("%dms (%dm %ds)", ms, minutes, seconds);
        }
    }

    private String formatMemory(Long kb) {
        if (kb == null || kb <= 0) return null;
        if (kb < 1024) {
            return kb + " KB";
        } else {
            double mb = kb / 1024.0;
            return String.format("%d KB (%.2f MB)", kb, mb);
        }
    }
}