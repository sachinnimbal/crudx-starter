package io.github.sachinnimbal.crudx.core.config;

import io.github.sachinnimbal.crudx.core.enums.CacheProvider;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "crudx.cache")
public class CrudXCacheProperties {

    /**
     * Enable/disable caching
     */
    private boolean enabled = false;

    /**
     * Cache provider: caffeine, redis, hazelcast, ehcache, memcached, custom
     */
    private CacheProvider provider = CacheProvider.CAFFEINE;

    /**
     * Default TTL in seconds (0 = no expiration)
     */
    private long ttlSeconds = 300;

    /**
     * Maximum cache size (entries)
     */
    private long maxSize = 10000;

    /**
     * Enable cache statistics
     */
    private boolean enableStatistics = true;

    /**
     * Cache key prefix
     */
    private String keyPrefix = "crudx";

    /**
     * Custom cache manager bean name (for custom implementations)
     */
    private String customManagerBean;

    /**
     * Redis specific configuration
     */
    private RedisConfig redis = new RedisConfig();

    /**
     * Hazelcast specific configuration
     */
    private HazelcastConfig hazelcast = new HazelcastConfig();

    /**
     * EhCache specific configuration
     */
    private EhCacheConfig ehcache = new EhCacheConfig();

    /**
     * Memcached specific configuration
     */
    private MemcachedConfig memcached = new MemcachedConfig();

    @Data
    public static class RedisConfig {
        private String host = "localhost";
        private int port = 6379;
        private String password;
        private int database = 0;
        private long timeoutMs = 3000;
    }

    @Data
    public static class HazelcastConfig {
        private String clusterName = "crudx-cluster";
        private List<String> members = new ArrayList<>();
    }

    @Data
    public static class EhCacheConfig {
        private String configLocation = "classpath:ehcache.xml";
    }

    @Data
    public static class MemcachedConfig {
        private String servers = "localhost:11211";
        private int connectionPoolSize = 10;
    }
}