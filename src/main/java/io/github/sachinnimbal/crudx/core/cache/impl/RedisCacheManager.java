package io.github.sachinnimbal.crudx.core.cache.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sachinnimbal.crudx.core.cache.CrudXCacheManager;
import io.github.sachinnimbal.crudx.core.cache.CrudXCacheStats;
import io.github.sachinnimbal.crudx.core.config.CrudXCacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@ConditionalOnProperty(prefix = "crudx.cache", name = "provider", havingValue = "redis")
public class RedisCacheManager implements CrudXCacheManager {

    private final RedisTemplate<String, Object> redisTemplate;
    private final CrudXCacheProperties properties;
    private final ObjectMapper objectMapper;

    public RedisCacheManager(RedisTemplate<String, Object> redisTemplate,
                             CrudXCacheProperties properties,
                             ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
        log.info("✓ Redis Cache Manager initialized ({}:{})",
                properties.getRedis().getHost(),
                properties.getRedis().getPort());
    }

    @Override
    public <T, ID> void put(String cacheName, ID key, T value) {
        String fullKey = buildKey(cacheName, key);

        if (properties.getTtlSeconds() > 0) {
            redisTemplate.opsForValue().set(fullKey, value,
                    properties.getTtlSeconds(), TimeUnit.SECONDS);
        } else {
            redisTemplate.opsForValue().set(fullKey, value);
        }

        log.trace("Redis cached: {}", fullKey);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T, ID> T get(String cacheName, ID key, Class<T> type) {
        String fullKey = buildKey(cacheName, key);
        Object value = redisTemplate.opsForValue().get(fullKey);

        if (value != null) {
            log.trace("Redis cache HIT: {}", fullKey);
            return (T) value;
        }

        log.trace("Redis cache MISS: {}", fullKey);
        return null;
    }

    @Override
    public <ID> void evict(String cacheName, ID key) {
        String fullKey = buildKey(cacheName, key);
        redisTemplate.delete(fullKey);
        log.trace("Redis evicted: {}", fullKey);
    }

    @Override
    public void clear(String cacheName) {
        String pattern = buildKey(cacheName, "*");
        Set<String> keys = redisTemplate.keys(pattern);

        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.info("Redis cleared cache: {} ({} keys)", cacheName, keys.size());
        }
    }

    @Override
    public <ID> boolean exists(String cacheName, ID key) {
        String fullKey = buildKey(cacheName, key);
        return redisTemplate.hasKey(fullKey);
    }

    @Override
    public CrudXCacheStats getStats(String cacheName) {
        String pattern = buildKey(cacheName, "*");
        Set<String> keys = redisTemplate.keys(pattern);

        return CrudXCacheStats.builder()
                .cacheName(cacheName)
                .size(keys.size())
                .build();
    }

    @Override
    public Set<String> getCacheNames() {
        Set<String> keys = redisTemplate.keys(properties.getKeyPrefix() + ":*");
        return keys.stream()
                .map(k -> k.split(":")[1])
                .collect(Collectors.toSet());
    }

    private String buildKey(String cacheName, Object key) {
        return properties.getKeyPrefix() + ":" + cacheName + ":" + key.toString();
    }
}