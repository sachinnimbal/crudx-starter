package io.github.sachinnimbal.crudx.core.config;

import io.github.sachinnimbal.crudx.core.enums.CacheProvider;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "crudx.cache")
public class CrudXCacheProperties {

    private boolean enabled = false;

    private CacheProvider provider = CacheProvider.CAFFEINE;

    private long ttlSeconds = 300;

    private long maxSize = 10000;

    private boolean enableStatistics = true;

    private String keyPrefix = "crudx";

    private String customManagerBean;

    private RedisConfig redis = new RedisConfig();

    private HazelcastConfig hazelcast = new HazelcastConfig();

    private EhCacheConfig ehcache = new EhCacheConfig();

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
