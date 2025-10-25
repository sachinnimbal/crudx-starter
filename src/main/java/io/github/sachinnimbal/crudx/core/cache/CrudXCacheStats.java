package io.github.sachinnimbal.crudx.core.cache;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CrudXCacheStats {
    private String cacheName;
    private long hitCount;
    private long missCount;
    private long evictionCount;
    private long size;
    private double hitRate;
    private long memoryUsageBytes;

    public double getHitRate() {
        long total = hitCount + missCount;
        return total == 0 ? 0.0 : (double) hitCount / total * 100;
    }
}
