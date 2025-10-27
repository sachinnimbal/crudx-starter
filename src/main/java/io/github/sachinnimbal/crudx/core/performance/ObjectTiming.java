package io.github.sachinnimbal.crudx.core.performance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 🔥 ULTRA-LIGHTWEIGHT Object-level Timing Model
 * ALL timing fields are String (formatted) for response compatibility
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectTiming {

    private String id; // Entity ID

    // 🔥 CRITICAL: All timing fields are String (formatted)
    private String mappingTime;    // e.g., "2 ms"
    private String validationTime; // e.g., "1 ms"
    private String writeTime;      // e.g., "20 ms"
    private String totalTime;      // e.g., "23 ms"
}