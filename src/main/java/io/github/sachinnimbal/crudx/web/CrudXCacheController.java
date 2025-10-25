package io.github.sachinnimbal.crudx.web;

import io.github.sachinnimbal.crudx.core.cache.CrudXCacheManager;
import io.github.sachinnimbal.crudx.core.cache.CrudXCacheStats;
import io.github.sachinnimbal.crudx.core.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("${crudx.cache.management-path:/crudx/cache}")
@ConditionalOnProperty(prefix = "crudx.cache", name = "enabled", havingValue = "true")
public class CrudXCacheController {

    @Autowired(required = false)
    private CrudXCacheManager cacheManager;

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, CrudXCacheStats>>> getStats() {
        if (cacheManager == null) {
            return ResponseEntity.ok(ApiResponse.error("Cache not enabled", HttpStatus.NOT_FOUND));
        }

        Map<String, CrudXCacheStats> stats = new HashMap<>();
        for (String cacheName : cacheManager.getCacheNames()) {
            stats.put(cacheName, cacheManager.getStats(cacheName));
        }

        return ResponseEntity.ok(ApiResponse.success(stats, "Cache statistics retrieved"));
    }

    @DeleteMapping("/clear/{cacheName}")
    public ResponseEntity<ApiResponse<Void>> clearCache(@PathVariable String cacheName) {
        if (cacheManager == null) {
            return ResponseEntity.ok(ApiResponse.error("Cache not enabled", HttpStatus.NOT_FOUND));
        }

        cacheManager.clear(cacheName);
        log.info("Cache cleared: {}", cacheName);

        return ResponseEntity.ok(ApiResponse.success(null, "Cache cleared: " + cacheName));
    }

    @DeleteMapping("/clear-all")
    public ResponseEntity<ApiResponse<Void>> clearAllCaches() {
        if (cacheManager == null) {
            return ResponseEntity.ok(ApiResponse.error("Cache not enabled", HttpStatus.NOT_FOUND));
        }

        for (String cacheName : cacheManager.getCacheNames()) {
            cacheManager.clear(cacheName);
        }

        log.info("All caches cleared");
        return ResponseEntity.ok(ApiResponse.success(null, "All caches cleared"));
    }
}
