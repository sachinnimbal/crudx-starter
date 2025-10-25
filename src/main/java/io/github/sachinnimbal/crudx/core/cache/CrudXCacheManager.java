package io.github.sachinnimbal.crudx.core.cache;

import java.util.Set;

public interface CrudXCacheManager {

    <T, ID> void put(String cacheName, ID key, T value);

    <T, ID> T get(String cacheName, ID key, Class<T> type);

    <ID> void evict(String cacheName, ID key);

    void clear(String cacheName);

    <ID> boolean exists(String cacheName, ID key);

    CrudXCacheStats getStats(String cacheName);

    Set<String> getCacheNames();
}
