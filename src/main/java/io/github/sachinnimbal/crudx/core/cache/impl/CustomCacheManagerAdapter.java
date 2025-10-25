package io.github.sachinnimbal.crudx.core.cache.impl;

import io.github.sachinnimbal.crudx.core.cache.CrudXCacheManager;
import io.github.sachinnimbal.crudx.core.cache.CrudXCacheStats;
import io.github.sachinnimbal.crudx.core.config.CrudXCacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;

import java.util.Set;

@Slf4j
@ConditionalOnProperty(prefix = "crudx.cache", name = "provider", havingValue = "custom")
public class CustomCacheManagerAdapter implements CrudXCacheManager {

    private final CrudXCacheManager delegate;

    public CustomCacheManagerAdapter(ApplicationContext context,
                                     CrudXCacheProperties properties) {
        String beanName = properties.getCustomManagerBean();

        if (beanName == null || beanName.isEmpty()) {
            throw new IllegalStateException(
                    "Custom cache provider selected but 'crudx.cache.custom-manager-bean' not configured");
        }

        try {
            this.delegate = context.getBean(beanName, CrudXCacheManager.class);
            log.info("✓ Custom Cache Manager loaded: {}", beanName);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load custom cache manager bean: " + beanName, e);
        }
    }

    @Override
    public <T, ID> void put(String cacheName, ID key, T value) {
        delegate.put(cacheName, key, value);
    }

    @Override
    public <T, ID> T get(String cacheName, ID key, Class<T> type) {
        return delegate.get(cacheName, key, type);
    }

    @Override
    public <ID> void evict(String cacheName, ID key) {
        delegate.evict(cacheName, key);
    }

    @Override
    public void clear(String cacheName) {
        delegate.clear(cacheName);
    }

    @Override
    public <ID> boolean exists(String cacheName, ID key) {
        return delegate.exists(cacheName, key);
    }

    @Override
    public CrudXCacheStats getStats(String cacheName) {
        return delegate.getStats(cacheName);
    }

    @Override
    public Set<String> getCacheNames() {
        return delegate.getCacheNames();
    }
}
