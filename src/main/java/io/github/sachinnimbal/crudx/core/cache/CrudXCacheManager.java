package io.github.sachinnimbal.crudx.core.cache;

import java.util.Set;

public interface CrudXCacheManager {

    /**
     * Store entity in cache
     */
    <T, ID> void put(String cacheName, ID key, T value);

    /**
     * Retrieve entity from cache
     */
    <T, ID> T get(String cacheName, ID key, Class<T> type);

    /**
     * Remove entity from cache
     */
    <ID> void evict(String cacheName, ID key);

    /**
     * Clear entire cache
     */
    void clear(String cacheName);

    /**
     * Check if key exists
     */
    <ID> boolean exists(String cacheName, ID key);

    /**
     * Get cache statistics
     */
    CrudXCacheStats getStats(String cacheName);

    /**
     * Get all cache names
     */
    Set<String> getCacheNames();
}