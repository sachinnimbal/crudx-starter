package io.github.sachinnimbal.crudx.core.cache.impl;

import com.hazelcast.core.DistributedObject;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.map.LocalMapStats;
import io.github.sachinnimbal.crudx.core.cache.CrudXCacheManager;
import io.github.sachinnimbal.crudx.core.cache.CrudXCacheStats;
import io.github.sachinnimbal.crudx.core.config.CrudXCacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@ConditionalOnProperty(prefix = "crudx.cache", name = "provider", havingValue = "hazelcast")
public class HazelcastCacheManager implements CrudXCacheManager {

    private final HazelcastInstance hazelcastInstance;
    private final CrudXCacheProperties properties;

    public HazelcastCacheManager(HazelcastInstance hazelcastInstance,
                                 CrudXCacheProperties properties) {
        this.hazelcastInstance = hazelcastInstance;
        this.properties = properties;
        log.info("✓ Hazelcast Cache Manager initialized (cluster: {})",
                properties.getHazelcast().getClusterName());
    }

    private IMap<String, Object> getCache(String cacheName) {
        return hazelcastInstance.getMap(cacheName);
    }

    @Override
    public <T, ID> void put(String cacheName, ID key, T value) {
        String fullKey = buildKey(key);

        if (properties.getTtlSeconds() > 0) {
            getCache(cacheName).put(fullKey, value,
                    properties.getTtlSeconds(), TimeUnit.SECONDS);
        } else {
            getCache(cacheName).put(fullKey, value);
        }

        log.trace("Hazelcast cached: {}", fullKey);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T, ID> T get(String cacheName, ID key, Class<T> type) {
        String fullKey = buildKey(key);
        Object value = getCache(cacheName).get(fullKey);
        log.trace("Hazelcast cache {}: {}", value != null ? "HIT" : "MISS", fullKey);
        return (T) value;
    }

    @Override
    public <ID> void evict(String cacheName, ID key) {
        String fullKey = buildKey(key);
        getCache(cacheName).remove(fullKey);
        log.trace("Hazelcast evicted: {}", fullKey);
    }

    @Override
    public void clear(String cacheName) {
        getCache(cacheName).clear();
        log.info("Hazelcast cleared cache: {}", cacheName);
    }

    @Override
    public <ID> boolean exists(String cacheName, ID key) {
        String fullKey = buildKey(key);
        return getCache(cacheName).containsKey(fullKey);
    }

    @Override
    public CrudXCacheStats getStats(String cacheName) {
        IMap<String, Object> cache = getCache(cacheName);
        LocalMapStats stats = cache.getLocalMapStats();

        return CrudXCacheStats.builder()
                .cacheName(cacheName)
                .hitCount(stats.getHits())
                .size(cache.size())
                .memoryUsageBytes(stats.getOwnedEntryMemoryCost())
                .build();
    }

    @Override
    public Set<String> getCacheNames() {
        return hazelcastInstance.getDistributedObjects().stream()
                .filter(obj -> obj instanceof IMap)
                .map(DistributedObject::getName)
                .collect(Collectors.toSet());
    }

    private String buildKey(Object key) {
        return properties.getKeyPrefix() + ":" + key.toString();
    }
}