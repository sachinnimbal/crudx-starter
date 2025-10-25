package io.github.sachinnimbal.crudx.core.cache.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.github.sachinnimbal.crudx.core.cache.CrudXCacheManager;
import io.github.sachinnimbal.crudx.core.cache.CrudXCacheStats;
import io.github.sachinnimbal.crudx.core.config.CrudXCacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@ConditionalOnProperty(prefix = "crudx.cache", name = "provider", havingValue = "caffeine", matchIfMissing = true)
public class CaffeineCacheManager implements CrudXCacheManager {

    private final Map<String, Cache<Object, Object>> caches = new ConcurrentHashMap<>();
    private final CrudXCacheProperties properties;

    public CaffeineCacheManager(CrudXCacheProperties properties) {
        this.properties = properties;
        log.info("✓ Caffeine Cache Manager initialized");
    }

    private Cache<Object, Object> getOrCreateCache(String cacheName) {
        return caches.computeIfAbsent(cacheName, name -> {
            Caffeine<Object, Object> builder = Caffeine.newBuilder()
                    .maximumSize(properties.getMaxSize());

            if (properties.getTtlSeconds() > 0) {
                builder.expireAfterWrite(properties.getTtlSeconds(), TimeUnit.SECONDS);
            }

            if (properties.isEnableStatistics()) {
                builder.recordStats();
            }

            log.info("Created Caffeine cache: {} (max size: {}, TTL: {}s)",
                    name, properties.getMaxSize(), properties.getTtlSeconds());

            return builder.build();
        });
    }

    @Override
    public <T, ID> void put(String cacheName, ID key, T value) {
        String fullKey = buildKey(key);
        getOrCreateCache(cacheName).put(fullKey, value);
        log.trace("Cached: {} -> {}", fullKey, value.getClass().getSimpleName());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T, ID> T get(String cacheName, ID key, Class<T> type) {
        String fullKey = buildKey(key);
        Object value = getOrCreateCache(cacheName).getIfPresent(fullKey);
        log.trace("Cache {} for key: {}", value != null ? "HIT" : "MISS", fullKey);
        return (T) value;
    }

    @Override
    public <ID> void evict(String cacheName, ID key) {
        String fullKey = buildKey(key);
        getOrCreateCache(cacheName).invalidate(fullKey);
        log.trace("Evicted from cache: {}", fullKey);
    }

    @Override
    public void clear(String cacheName) {
        Cache<Object, Object> cache = caches.get(cacheName);
        if (cache != null) {
            cache.invalidateAll();
            log.info("Cleared cache: {}", cacheName);
        }
    }

    @Override
    public <ID> boolean exists(String cacheName, ID key) {
        String fullKey = buildKey(key);
        return getOrCreateCache(cacheName).getIfPresent(fullKey) != null;
    }

    @Override
    public CrudXCacheStats getStats(String cacheName) {
        Cache<Object, Object> cache = caches.get(cacheName);
        if (cache == null) {
            return CrudXCacheStats.builder().cacheName(cacheName).build();
        }

        CacheStats stats = cache.stats();
        return CrudXCacheStats.builder()
                .cacheName(cacheName)
                .hitCount(stats.hitCount())
                .missCount(stats.missCount())
                .evictionCount(stats.evictionCount())
                .size(cache.estimatedSize())
                .hitRate(stats.hitRate() * 100)
                .build();
    }

    @Override
    public Set<String> getCacheNames() {
        return new HashSet<>(caches.keySet());
    }

    private String buildKey(Object key) {
        return properties.getKeyPrefix() + ":" + key.toString();
    }
}
